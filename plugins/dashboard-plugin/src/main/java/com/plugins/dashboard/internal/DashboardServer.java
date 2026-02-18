package com.plugins.dashboard.internal;

import com.framework.core.media.MediaTranscoder;
import com.framework.common.auth.UserManager;
import com.framework.common.auth.AuthManager;
import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import com.framework.services.stats.StatisticsManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpContext;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class DashboardServer implements com.framework.api.WebServer {
    private static final Logger logger = LoggerFactory.getLogger(DashboardServer.class);
    private final Kernel kernel;
    private HttpServer server;
    private final Gson gson = new Gson();
    private static final int PORT = 6875;

    // Map to track registered contexts for unregistering
    private final Map<String, HttpContext> registeredContexts = new java.util.concurrent.ConcurrentHashMap<>();

    // Brute-force protection: track failed login attempts per IP
    private final Map<String, long[]> loginAttempts = new java.util.concurrent.ConcurrentHashMap<>();
    // loginAttempts value: [failCount, lockoutTimestamp (epoch ms)]
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000; // 15 minutes

    public DashboardServer(Kernel kernel) {
        this.kernel = kernel;
    }

    public enum Role {
        ADMIN, GUEST, ANY
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);

            // Register as WebServer service (Interface)
            kernel.registerService(com.framework.api.WebServer.class, this);
            kernel.registerService(DashboardServer.class, this); // Legacy

            // --- Auth APIs ---
            logger.info("Registering PUBLIC context: /api/login");
            server.createContext("/api/login", new LoginHandler());
            server.createContext("/api/logout", new LogoutHandler());
            logger.info("Registering PUBLIC context: /login");
            server.createContext("/login", new HtmlHandler("web/login.html")); // Must NOT be wrapped in AuthWrapper!
            server.createContext("/", new HtmlHandler("web/auth.html")); // Root points to Auth check
            server.createContext("/404.html", new HtmlHandler("web/404.html"));
            server.createContext("/403.html", new HtmlHandler("web/403.html"));

            // --- Web Views (HTML) ---
            createAuthorizedContext("/status", new HtmlHandler("web/status.html"), Role.ADMIN);
            createAuthorizedContext("/media", new HtmlHandler("web/media_manager.html"), Role.ADMIN);
            createAuthorizedContext("/members", new HtmlHandler("web/members.html"), Role.ADMIN);
            createAuthorizedContext("/commands", new HtmlHandler("web/commands.html"), Role.ADMIN);
            createAuthorizedContext("/settings", new HtmlHandler("web/settings.html"), Role.ADMIN);
            createAuthorizedContext("/statistics", new HtmlHandler("web/statistics.html"), Role.ADMIN);
            createAuthorizedContext("/database", new HtmlHandler("web/database.html"), Role.ADMIN);
            createAuthorizedContext("/viewer", new HtmlHandler("web/viewer.html"), Role.ANY);

            // --- Database API ---
            createAuthorizedContext("/api/database/tables", new DatabaseApiHandler(), Role.ADMIN);
            createAuthorizedContext("/api/database/query", new DatabaseQueryHandler(), Role.ADMIN);

            // --- Static Assets (JS, CSS) ---
            // Must NOT be wrapped in AuthWrapper to allow public access
            server.createContext("/js", new StaticFileHandler("web/js", "/js"));
            server.createContext("/css", new StaticFileHandler("web/css", "/css"));
            server.createContext("/wiki", new StaticFileHandler("web/wiki", "/wiki"));

            // Share Handler (Public)
            server.createContext("/share", new ShareHandler());
            server.createContext("/f", new ShareHandler()); // Alias for frontend compatibility

            // --- Data APIs ---
            // These remain PROTECTED by AuthWrapper
            createContext("/api/status", new ApiStatusHandler());
            // Guest login must be public!
            server.createContext("/api/guest", new GuestUserHandler());
            createAuthorizedContext("/api/media", new MediaApiHandler(), Role.ANY);
            createContext("/api/history", new HistoryApiHandler());
            createContext("/api/surgeon", new SurgeonHandler());
            createContext("/api/creators", new CreatorsApiHandler());
            createAuthorizedContext("/api/session", new SessionApiHandler(), Role.ANY);
            createContext("/api/settings", new SettingsHandler());
            createContext("/api/members", new MembersApiHandler());
            createContext("/api/plugins", new PluginSettingsHandler());

            // --- Action APIs ---
            createAuthorizedContext("/api/command", new CommandApiHandler(), Role.ADMIN);
            createAuthorizedContext("/push", new PushHandler(), Role.ADMIN);
            createAuthorizedContext("/api/torrent", new TorrentPushHandler(), Role.ADMIN);

            // --- Streaming & Files ---
            createAuthorizedContext("/media/file", new MediaFileHandler(), Role.ANY);
            createAuthorizedContext("/media/thumb", new ThumbnailHandler(), Role.ANY);

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            server.start();
            logger.info("Dashboard Server running on port {}", PORT);
        } catch (IOException e) {
            logger.error("Failed to start WebServer", e);
        }
    }

    /**
     * Creates an authenticated context on the HTTP server.
     * Made public so other plugins can register their own routes via the Kernel
     * service registry.
     */
    public void createContext(String path, HttpHandler handler) {
        // Log this?
        createAuthorizedContext(path, handler, Role.ANY);
    }

    public void createAuthorizedContext(String path, HttpHandler handler, Role role) {
        logger.info("Registering PROTECTED context: {} (Role: {})", path, role);
        server.createContext(path, new AuthWrapper(handler, role));
    }

    /**
     * Creates a public (unauthenticated) context on the HTTP server.
     * Used for HTML pages that handle auth via JavaScript.
     */
    public void createPublicContext(String path, HttpHandler handler) {
        logger.info("Registering PUBLIC context: {}", path);
        server.createContext(path, handler);
    }

    public void stop() {
        if (server != null)
            server.stop(0);
    }

    // =================================================================================
    // FILE SERVING (Range Requests)
    // =================================================================================

    /**
     * Serves a file with full HTTP Range Request support (RFC 7233).
     * Enables video seeking, partial downloads, and faster initial playback.
     */
    private void serveFileWithRanges(HttpExchange ex, File f) throws IOException {
        if (!f.exists() || !f.isFile()) {
            sendError(ex, 404, "File not found");
            return;
        }

        long fileLen = f.length();
        String mime = Files.probeContentType(f.toPath());
        if (mime == null)
            mime = "application/octet-stream";

        ex.getResponseHeaders().add("Content-Type", mime);
        ex.getResponseHeaders().add("Accept-Ranges", "bytes");

        String rangeHeader = ex.getRequestHeaders().getFirst("Range");

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            // Parse Range header: "bytes=start-end" or "bytes=start-"
            try {
                String rangeSpec = rangeHeader.substring(6);
                long start, end;

                if (rangeSpec.startsWith("-")) {
                    // Suffix range: "-500" means last 500 bytes
                    long suffix = Long.parseLong(rangeSpec.substring(1));
                    start = Math.max(0, fileLen - suffix);
                    end = fileLen - 1;
                } else {
                    String[] parts = rangeSpec.split("-", 2);
                    start = Long.parseLong(parts[0]);
                    end = (parts.length > 1 && !parts[1].isEmpty())
                            ? Long.parseLong(parts[1])
                            : fileLen - 1;
                }

                // Clamp
                if (start < 0 || start >= fileLen || end < start) {
                    ex.getResponseHeaders().add("Content-Range", "bytes */" + fileLen);
                    ex.sendResponseHeaders(416, -1); // Range Not Satisfiable
                    return;
                }
                end = Math.min(end, fileLen - 1);
                long contentLen = end - start + 1;

                ex.getResponseHeaders().add("Content-Range",
                        "bytes " + start + "-" + end + "/" + fileLen);
                ex.sendResponseHeaders(206, contentLen);

                try (RandomAccessFile raf = new RandomAccessFile(f, "r");
                        OutputStream os = ex.getResponseBody()) {
                    raf.seek(start);
                    byte[] buf = new byte[8192];
                    long remaining = contentLen;
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int read = raf.read(buf, 0, toRead);
                        if (read == -1)
                            break;
                        os.write(buf, 0, read);
                        remaining -= read;
                    }
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                // Malformed Range header — fall through to full response
                serveFullFile(ex, f, mime, fileLen);
            }
        } else {
            // No Range header — serve full file
            serveFullFile(ex, f, mime, fileLen);
        }
    }

    private void serveFullFile(HttpExchange ex, File f, String mime, long fileLen) throws IOException {
        ex.sendResponseHeaders(200, fileLen);
        try (FileInputStream fis = new FileInputStream(f);
                OutputStream os = ex.getResponseBody()) {
            fis.transferTo(os);
        }
    }

    // =================================================================================
    // HANDLERS
    // =================================================================================

    private class AuthWrapper implements HttpHandler {
        private final HttpHandler inner;
        private final Role requiredRole;
        // Whitelist for paths that MUST be public even if accidentally wrapped
        private static final Set<String> WHITELIST = Set.of(
                "/login", "/api/login", "/auth.html", "/403.html", "/404.html",
                // Allowed for client-side auth handling (Navigation fix)
                "/status", "/viewer", "/media", "/members", "/commands", "/settings", "/statistics", "/database",
                "/manga", "/manga/", "/manga/css", "/manga/js");

        public AuthWrapper(HttpHandler inner) {
            this(inner, Role.ADMIN);
        }

        public AuthWrapper(HttpHandler inner, Role requiredRole) {
            this.inner = inner;
            this.requiredRole = requiredRole;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();

            // Failsafe: If a public path is wrapped, allow it immediately
            if (path.equals("/") || WHITELIST.stream().anyMatch(path::startsWith)) {
                inner.handle(ex);
                return;
            }

            String token = ex.getRequestHeaders().getFirst("X-MAF-Token");
            String authHeader = ex.getRequestHeaders().getFirst("Authorization");

            AuthManager auth = kernel.getAuthManager();
            boolean apiKeyAuth = false;

            // Check Bearer Token (Session API Key)
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String potentialKey = authHeader.substring(7).trim();
                if (auth.isValidApiKey(potentialKey)) {
                    apiKeyAuth = true;
                    // API Key grants ADMIN access for this session
                }
            }

            if (token == null && !apiKeyAuth) {
                // Try from query for downloads/previews
                token = parseQuery(ex.getRequestURI().getQuery()).get("token");
            }

            // 1. Check if token is valid at all
            if (!apiKeyAuth && (token == null || !auth.isValidToken(token))) {
                logger.warn("Auth failed: Token invalid or missing. Path: {}", path);
                if (path.startsWith("/api/") || path.equals("/push")) {
                    sendError(ex, 401, "Unauthorized - Please login");
                } else {
                    // Serve Authentication Interstitial (DDoS Protection / Auth Check)
                    serveAuthPage(ex);
                }
                return;
            }

            // 2. Check if token has required role
            boolean authorized = false;
            if (apiKeyAuth) {
                authorized = true; // API Key is Admin
            } else if (requiredRole == Role.ANY) {
                authorized = true;
            } else if (requiredRole == Role.ADMIN) {
                authorized = auth.isAdmin(token);
            } else if (requiredRole == Role.GUEST) {
                authorized = auth.isGuest(token);
            }

            if (authorized) {
                inner.handle(ex);
            } else {
                logger.warn("Access Denied for path: {} (Role required: {})", path, requiredRole);
                if (path.startsWith("/api/") || path.equals("/push")) {
                    sendError(ex, 403, "Forbidden - " + requiredRole + " role required");
                } else {
                    // Redirect HTML pages to 403 Access Denied
                    ex.getResponseHeaders().add("Location", "/403.html");
                    ex.sendResponseHeaders(302, -1);
                }
            }
        }
    }

    private void serveAuthPage(HttpExchange ex) throws IOException {
        File f = new File("web/auth.html");
        String resp = f.exists() ? Files.readString(f.toPath())
                : "<h1>Authenticating...</h1><script>window.location.replace('/login');</script>";
        byte[] b = resp.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "POST expected");
                return;
            }

            // --- Brute-force protection ---
            String clientIp = ex.getRemoteAddress().getAddress().getHostAddress();
            long[] attempts = loginAttempts.get(clientIp);
            if (attempts != null && attempts[0] >= MAX_LOGIN_ATTEMPTS) {
                long remaining = (attempts[1] + LOCKOUT_DURATION_MS) - System.currentTimeMillis();
                if (remaining > 0) {
                    int mins = (int) (remaining / 60000) + 1;
                    sendJson(ex, new AuthManager.AuthResult(false, null, null,
                            "Too many failed attempts. Try again in " + mins + " minute(s)."));
                    return;
                } else {
                    // Lockout expired, reset
                    loginAttempts.remove(clientIp);
                }
            }

            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = gson.fromJson(isr, JsonObject.class);
                String user = body.get("user").getAsString();
                String pass = body.get("pass").getAsString();

                AuthManager.AuthResult result = kernel.getAuthManager().createSession(user, pass);

                // Fallback: Check for guest users in auth.json if core auth failed
                if (!result.success) {
                    try {
                        File authFile = new File("tools/auth.json");
                        if (authFile.exists()) {
                            JsonObject authData = gson.fromJson(Files.readString(authFile.toPath()), JsonObject.class);
                            if (authData.has("guestUsers")) {
                                JsonObject guests = authData.getAsJsonObject("guestUsers");
                                if (guests.has(user) && guests.get(user).getAsString().equals(pass)) {
                                    result = kernel.getAuthManager().createGuestSession();
                                    logger.info("Guest login successful for user: {}", user);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Failed to check guest auth", e);
                    }
                }

                // Track failed attempts
                if (!result.success) {
                    long[] a = loginAttempts.computeIfAbsent(clientIp, k -> new long[] { 0, 0 }); // Changed int[] to
                                                                                                  // long[]
                    a[0]++;
                    a[1] = System.currentTimeMillis(); // Fixed timestamp assignment
                    if (a[0] >= MAX_LOGIN_ATTEMPTS) {
                        logger.warn("\uD83D\uDEA8 IP {} locked out after {} failed login attempts", clientIp, a[0]);
                    }
                } else {
                    // Successful login, reset attempts
                    loginAttempts.remove(clientIp);
                }

                sendJson(ex, result);
            } catch (Exception e) {
                sendError(ex, 400, "Bad Request");
            }
        }
    }

    private class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String token = ex.getRequestHeaders().getFirst("X-MAF-Token");
            if (token == null)
                token = parseQuery(ex.getRequestURI().getQuery()).get("token");
            kernel.getAuthManager().revokeSession(token);
            sendJson(ex, Map.of("success", true));
        }
    }

    private class CommandApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Only POST allowed");
                return;
            }
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject json = gson.fromJson(isr, JsonObject.class);
                String cmdLine = json.has("cmd") ? json.get("cmd").getAsString() : "";

                String responseMsg = processCommand(cmdLine);
                sendJson(ex, Map.of("success", true, "response", responseMsg));
            } catch (Exception e) {
                logger.error("Command Error", e);
                sendError(ex, 500, e.getMessage());
            }
        }

        private String processCommand(String line) {
            if (line == null || line.trim().isEmpty())
                return "Empty command";
            String[] parts = line.trim().split("\\s+");
            String cmd = parts[0].toLowerCase();
            String arg = parts.length > 1 ? line.substring(cmd.length()).trim() : ""; // Rest der Zeile als Argument

            // 1. Suche via Party Sources (Coomer/Kemono) - Legacy commands
            if (cmd.equals("/cm") || cmd.equals("/km")) {
                String source = cmd.equals("/km") ? "kemono" : "coomer";
                String query = arg.isEmpty() ? "all" : arg;
                int amount = 1;

                QueueTask task = new QueueTask("SEARCH_BATCH");
                task.addParameter("query", query);
                task.addParameter("amount", amount);
                task.addParameter("source", source);
                kernel.getQueueManager().addTask(task);
                return "✅ Suche gestartet (" + source + "): " + query;
            }

            // 1.5 Check kernel command registry (for /dl and other plugin commands)
            if (kernel.getCommandRegistry() != null && kernel.getCommandRegistry().containsKey(cmd)) {
                // Execute via kernel command registry
                String[] cmdArgs = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
                kernel.getCommandRegistry().get(cmd).handle(0L, null, cmd, cmdArgs);
                return "✅ Command executed: " + cmd;
            }

            // 2. YouTube Download
            if (cmd.equals("/yt")) {
                if (parts.length < 3) {
                    return "❌ Usage: /yt video <URL> or /yt channel <URL> <count> or /yt playlist <URL> <count>";
                }

                String type = parts[1].toLowerCase(); // video, channel, playlist
                String url = parts[2];
                int amount = 1;

                if (parts.length > 3) {
                    try {
                        amount = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException e) {
                        amount = 1;
                    }
                }

                QueueTask task = new QueueTask("youtube");
                task.addParameter("type", type);
                task.addParameter("query", url);
                task.addParameter("amount", amount);
                kernel.getQueueManager().addTask(task);

                String msg = "🎥 YouTube " + type + " queued: " + url;
                if (amount > 1) {
                    msg += " (" + amount + " items)";
                }
                return msg;
            }

            // 3. Booru Shortcuts (R34, XBooru, Safebooru)
            if (cmd.equals("/r34") || cmd.equals("/xb") || cmd.equals("/sb")) {
                String source = cmd.substring(1); // r34, xb, sb
                String query = arg.isEmpty() ? "all" : arg;

                QueueTask task = new QueueTask("BOORU_BATCH");
                task.addParameter("query", query);
                task.addParameter("amount", 10);
                task.addParameter("source", source);
                kernel.getQueueManager().addTask(task);
                return "✅ Booru Suche gestartet (" + source + "): " + query;
            }

            // 3. CoreCommands Plugin Integration (Info & Tools)
            com.plugins.CoreCommandsPlugin ccp = kernel.getService(com.plugins.CoreCommandsPlugin.class);

            if (ccp != null) {
                if (cmd.equals("/status"))
                    return ccp.getStatusText();
                if (cmd.equals("/help"))
                    return ccp.getHelpText();
                if (cmd.equals("/queue"))
                    return ccp.getQueueText();
                if (cmd.equals("/log"))
                    return ccp.getLogText();
                if (cmd.equals("/clean"))
                    return ccp.executeClean();
                if (cmd.equals("/stop")) {
                    new Thread(() -> System.exit(0)).start();
                    return "🛑 System fährt herunter...";
                }
            }

            // 4. Queue Steuerung (Fallback)
            if (cmd.equals("/pause")) {
                kernel.getQueueManager().setPaused(true);
                return "⏸️ Queue pausiert.";
            }
            if (cmd.equals("/resume")) {
                kernel.getQueueManager().setPaused(false);
                return "▶️ Queue läuft weiter.";
            }
            if (cmd.equals("/clean_queue")) { // Umbenannt um Konflikt mit /clean (Disk) zu vermeiden
                kernel.getQueueManager().clearQueue();
                return "🗑️ Queue geleert.";
            }

            return "❌ Unbekannter Befehl: " + cmd;
        }
    }

    private class PushHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject json = gson.fromJson(isr, JsonObject.class);
                QueueTask task = new QueueTask("BROWSER_BATCH");

                List<String> urls = new ArrayList<>();
                if (json.has("urls")) {
                    for (JsonElement e : json.getAsJsonArray("urls"))
                        urls.add(e.getAsString());
                } else if (json.has("url")) {
                    urls.add(json.get("url").getAsString());
                }

                if (urls.isEmpty()) {
                    sendError(ex, 400, "No URLs provided");
                    return;
                }

                task.addParameter("urls", urls);
                task.addParameter("folder", json.has("folder") ? json.get("folder").getAsString() : "BrowserDump");
                if (json.has("cookies"))
                    task.addParameter("cookies", json.get("cookies").getAsString());
                if (json.has("referer"))
                    task.addParameter("referer", json.get("referer").getAsString());

                kernel.getQueueManager().addTask(task);
                sendJson(ex, Map.of("status", "ok", "count", urls.size()));
            } catch (Exception e) {
                logger.error("Push Error", e);
                sendError(ex, 500, "Internal Error");
            }
        }
    }

    private class TorrentPushHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject json = gson.fromJson(isr, JsonObject.class);
                String magnet = json.get("magnet").getAsString();
                QueueTask task = new QueueTask("DIRECT_DOWNLOAD");
                task.addParameter("url", magnet);
                task.addParameter("folder", "Torrents");

                PipelineItem item = new PipelineItem(magnet, "Magnet_Upload_" + System.currentTimeMillis(), task);
                kernel.getPipelineManager().submit(item);
                sendJson(ex, Map.of("status", "queued", "type", "magnet"));
            } catch (Exception e) {
                sendError(ex, 500, e.getMessage());
            }
        }
    }

    private class DatabaseApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                com.framework.services.database.DatabaseService db = kernel.getDatabaseService();
                if (db != null) {
                    // Fetch tables using JDBI
                    List<String> tables = db.getJdbi()
                            .withHandle(handle -> handle.createQuery("SHOW TABLES").mapTo(String.class).list());
                    sendJson(ex, tables);
                } else {
                    sendError(ex, 503, "Database service unavailable");
                }
            } catch (Exception e) {
                logger.error("Database API Error", e);
                sendError(ex, 500, e.getMessage());
            }
        }
    }

    private class DatabaseQueryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "POST expected");
                return;
            }

            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = gson.fromJson(isr, JsonObject.class);
                String query = body.get("query").getAsString().trim();

                com.framework.services.database.DatabaseService db = kernel.getDatabaseService();
                if (db == null) {
                    sendError(ex, 503, "Database service unavailable");
                    return;
                }

                if (query.toLowerCase().startsWith("select") || query.toLowerCase().startsWith("show")) {
                    List<Map<String, Object>> rows = db.getJdbi()
                            .withHandle(handle -> handle.createQuery(query).mapToMap().list());

                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("type", "select");
                    result.addProperty("rowCount", rows.size());

                    JsonArray columns = new JsonArray();
                    if (!rows.isEmpty()) {
                        rows.get(0).keySet().forEach(columns::add);
                    }
                    result.add("columns", columns);

                    JsonArray data = new JsonArray();
                    for (Map<String, Object> row : rows) {
                        JsonArray rowArr = new JsonArray();
                        if (!rows.isEmpty()) {
                            // Ensure column order matches header
                            for (String col : rows.get(0).keySet()) {
                                Object val = row.get(col);
                                if (val == null)
                                    rowArr.add(com.google.gson.JsonNull.INSTANCE);
                                else
                                    rowArr.add(val.toString());
                            }
                        }
                        data.add(rowArr);
                    }
                    result.add("data", data);

                    sendJson(ex, result);
                } else {
                    int affected = db.getJdbi().withHandle(handle -> handle.createUpdate(query).execute());
                    sendJson(ex, Map.of("success", true, "type", "update", "affectedRows", affected));
                }
            } catch (Exception e) {
                logger.error("Query Execution Fail", e);
                sendJson(ex, Map.of("success", false, "error", e.getMessage()));
            }
        }
    }

    private class SettingsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJson(ex, kernel.getConfigManager().getConfig());
            } else if (ex.getRequestMethod().equalsIgnoreCase("POST")) {
                try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                    com.framework.core.config.Configuration newConfig = gson.fromJson(isr,
                            com.framework.core.config.Configuration.class);
                    if (newConfig != null) {
                        kernel.getConfigManager().updateConfig(newConfig);
                        sendJson(ex, Map.of("success", true));
                    } else
                        sendError(ex, 400, "Bad Config");
                } catch (Exception e) {
                    sendError(ex, 500, e.getMessage());
                }
            }
        }
    }

    private class HtmlHandler implements HttpHandler {
        private final String path;

        public HtmlHandler(String path) {
            this.path = path;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            File f = new File(path);
            String resp = f.exists() ? Files.readString(f.toPath()) : "<h1>File not found: " + path + "</h1>";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    private class MembersApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
            String q = params.get("q");
            List<UserManager.UserData> users = kernel.getUserManager().searchUsers(q);
            JsonArray arr = new JsonArray();
            for (UserManager.UserData u : users) {
                JsonObject o = gson.toJsonTree(u).getAsJsonObject();
                String role = "USER";
                if (kernel.getAuthManager().isAdmin(u.id))
                    role = "ADMIN";
                else if (kernel.getAuthManager().isGuest(u.id))
                    role = "GUEST";
                o.addProperty("role", role);
                if (u.languageCode != null)
                    o.addProperty("lang", u.languageCode);
                arr.add(o);
            }
            sendJson(ex, arr);
        }
    }

    private class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            JsonObject root = new JsonObject();
            JsonObject history = kernel.getSystemStatsService().getHistoryJson();
            if (!history.has("net_dl"))
                history.add("net_dl", new JsonArray());
            if (!history.has("net_ul"))
                history.add("net_ul", new JsonArray());
            root.add("history", history);

            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            double cpuLoad = 0;
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                cpuLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad() * 100;
            }
            long totalRam = Runtime.getRuntime().totalMemory() / 1024 / 1024;
            long freeRam = Runtime.getRuntime().freeMemory() / 1024 / 1024;

            JsonObject system = new JsonObject();
            system.addProperty("cpu", cpuLoad);
            system.addProperty("ram_used", totalRam - freeRam);
            system.addProperty("ram_max", totalRam);
            system.addProperty("disk_free_gb", new File(".").getFreeSpace() / 1024 / 1024 / 1024);
            root.add("system", system);

            JsonArray qArr = new JsonArray();
            for (QueueTask t : kernel.getQueueManager().getQueueTasks()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", t.getName());
                o.addProperty("type", t.getType());
                o.addProperty("total", t.getTotalItems());
                o.addProperty("ul", t.getProcessedItems());
                o.addProperty("status", t.getStatus().toString());
                qArr.add(o);
            }
            root.add("queue", qArr);

            JsonObject stats = new JsonObject();
            StatisticsManager sm = kernel.getStatisticsManager();
            stats.addProperty("total_req", sm.getTotalRequests());
            stats.addProperty("total_files", sm.getTotalFiles());
            stats.addProperty("total_dl_mb", sm.getTotalBytesDownloaded() / 1024 / 1024);
            stats.addProperty("total_ul_mb", sm.getTotalBytesUploaded() / 1024 / 1024);
            stats.addProperty("total_ffmpeg_min", sm.getTotalFfmpegTime() / 60);
            stats.addProperty("avg_proc_time_sec", 0);
            root.add("stats", stats);

            JsonArray creators = new JsonArray();
            kernel.getStatisticsManager().getTopCreatorsDetailed(10).forEach((name, s) -> {
                JsonObject c = new JsonObject();
                c.addProperty("name", name);
                c.addProperty("count", s.total());
                c.addProperty("img", s.images());
                c.addProperty("vid", s.videos());
                c.addProperty("gif", s.gifs());
                c.addProperty("last_active", s.lastActivity());
                creators.add(c);
            });
            root.add("creators", creators);

            JsonObject pipe = new JsonObject();
            pipe.add("stage_dl", buildStageJson(kernel.getPipelineManager().getCurrentDlItem()));
            pipe.add("stage_proc", buildStageJson(kernel.getPipelineManager().getCurrentProcItem()));
            pipe.add("stage_ul", buildStageJson(kernel.getPipelineManager().getCurrentUlItem()));
            root.add("pipeline", pipe);

            root.addProperty("surgeon_mode", kernel.getPipelineManager().isSurgeonMode());
            root.addProperty("queue_paused", kernel.getQueueManager().isPaused());

            sendJson(ex, root);
        }

        private JsonObject buildStageJson(PipelineItem item) {
            JsonObject o = new JsonObject();
            o.add("queue", new JsonArray());
            if (item != null) {
                o.addProperty("active", item.getOriginalName());
                o.addProperty("active_bool", true);
                o.addProperty("total", 100);
                o.addProperty("current", 0);
                o.addProperty("speed", 0);
                o.addProperty("eta", 0);
            } else {
                o.addProperty("active_bool", false);
                o.addProperty("active", "IDLE");
            }
            return o;
        }
    }

    private class MediaApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
            String action = params.getOrDefault("action", "list");
            String method = ex.getRequestMethod();

            if (method.equalsIgnoreCase("GET")) {
                if ("list".equals(action))
                    sendJson(ex, scanDir(new File("media_cache")));
                else if ("delete".equals(action))
                    sendJson(ex, Map.of("success", deleteItem(params.get("path"))));
                else if ("rename".equals(action))
                    sendJson(ex, Map.of("success", renameItem(params.get("path"), params.get("newName"))));
                else if ("mkdir".equals(action))
                    sendJson(ex, Map.of("success", new File("media_cache", params.get("name")).mkdirs()));
                else if ("read_text".equals(action))
                    handleReadText(ex, params.get("path"));
                else if ("share".equals(action))
                    handleShare(ex, params.get("path"));
                else if ("paste".equals(action))
                    handlePaste(ex, params);
                else if ("pack".equals(action))
                    handlePack(ex, params);
                else if ("unpack".equals(action))
                    handleUnpack(ex, params);
            } else if (method.equalsIgnoreCase("POST")) {
                if ("upload".equals(action))
                    handleUpload(ex, params.getOrDefault("folder", ""));
                else if ("save_text".equals(action))
                    handleSaveText(ex);
                else if ("delete".equals(action)) {
                    try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                        JsonObject body = gson.fromJson(isr, JsonObject.class);
                        JsonArray paths = body.getAsJsonArray("paths");
                        int successCount = 0;
                        if (paths != null) {
                            for (JsonElement p : paths) {
                                if (deleteItem(p.getAsString()))
                                    successCount++;
                            }
                        }
                        sendJson(ex, Map.of("success", true, "count", successCount));
                    } catch (Exception e) {
                        sendJson(ex, Map.of("success", false, "error", e.getMessage()));
                    }
                }
            }
        }

        private JsonArray scanDir(File dir) {
            JsonArray arr = new JsonArray();
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.stream(files)
                        .sorted((a, b) -> Boolean.compare(b.isDirectory(), a.isDirectory()))
                        .forEach(f -> {
                            JsonObject o = new JsonObject();
                            o.addProperty("name", f.getName());
                            o.addProperty("type", f.isDirectory() ? "dir" : "file");
                            o.addProperty("size", f.length());
                            o.addProperty("path", f.getAbsolutePath());
                            o.addProperty("lastMod", f.lastModified());
                            if (f.isDirectory())
                                o.add("children", scanDir(f));
                            arr.add(o);
                        });
            }
            return arr;
        }

        private void handleReadText(HttpExchange ex, String path) throws IOException {
            File f = new File(path);
            File mediaCacheDir = new File("media_cache").getCanonicalFile();

            if (f.exists() && isWithinDirectory(f, mediaCacheDir)) {
                sendJson(ex, Map.of("content", Files.readString(f.toPath(), StandardCharsets.UTF_8)));
            } else {
                sendError(ex, 404, "File not found");
            }
        }

        private void handleSaveText(HttpExchange ex) throws IOException {
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = gson.fromJson(isr, JsonObject.class);
                File f = new File(body.get("path").getAsString());
                File mediaCacheDir = new File("media_cache").getCanonicalFile();

                if (isWithinDirectory(f, mediaCacheDir)) {
                    Files.writeString(f.toPath(), body.get("content").getAsString(), StandardCharsets.UTF_8);
                    sendJson(ex, Map.of("success", true));
                } else {
                    sendJson(ex, Map.of("success", false, "error", "Invalid path"));
                }
            } catch (Exception e) {
                sendJson(ex, Map.of("error", e.getMessage()));
            }
        }

        private void handleUpload(HttpExchange exchange, String folderName) throws IOException {
            try {
                DiskFileItemFactory factory = new DiskFileItemFactory();
                FileUpload upload = new FileUpload(factory);
                List<FileItem> items = upload.parseRequest(new HttpExchangeRequestContext(exchange));
                File targetDir = new File("media_cache");
                if (!folderName.isEmpty())
                    targetDir = new File(targetDir, folderName.replaceAll("[^a-zA-Z0-9._-]", "_"));
                if (!targetDir.exists())
                    targetDir.mkdirs();
                for (FileItem item : items)
                    if (!item.isFormField())
                        item.write(new File(targetDir, new File(item.getName()).getName()));
                sendJson(exchange, Map.of("success", true));
            } catch (Exception e) {
                sendError(exchange, 500, "Upload failed");
            }
        }

        private boolean isWithinDirectory(File file, File directory) throws IOException {
            String canonicalFile = file.getCanonicalPath();
            String canonicalDir = directory.getCanonicalPath();
            return canonicalFile.startsWith(canonicalDir);
        }

        private boolean deleteItem(String path) {
            try {
                if (path == null)
                    return false;
                File f = new File(path);
                File mediaCacheDir = new File("media_cache").getCanonicalFile();
                if (!isWithinDirectory(f, mediaCacheDir)) {
                    logger.warn("Delete rejected: Path is outside media_cache: {}", path);
                    return false;
                }

                if (!f.exists()) {
                    logger.warn("Delete failed: File not found: {}", path);
                    return false;
                }

                boolean result;
                if (f.isDirectory()) {
                    result = deleteRecursive(f);
                } else {
                    result = f.delete();
                }

                if (result) {
                    deleteThumbnail(f, f.isDirectory());
                }
                return result;
            } catch (IOException e) {
                logger.error("Delete failed with exception", e);
                return false;
            }
        }

        private void deleteThumbnail(File origin, boolean isDir) {
            try {
                File mediaCache = new File("media_cache").getCanonicalFile();
                File absOrigin = origin.getCanonicalFile();
                if (absOrigin.getPath().startsWith(mediaCache.getPath())) {
                    String rel = absOrigin.getPath().substring(mediaCache.getPath().length());
                    if (rel.startsWith(File.separator))
                        rel = rel.substring(1);

                    File thumbsDir = new File(mediaCache, ".thumbs");
                    File target;
                    if (isDir) {
                        target = new File(thumbsDir, rel);
                        deleteRecursive(target);
                    } else {
                        target = new File(thumbsDir, rel + ".thumb.jpg");
                        if (target.exists())
                            target.delete();
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        private boolean deleteRecursive(File file) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!deleteRecursive(child)) {
                            return false;
                        }
                    }
                }
            }
            return file.delete();
        }

        private boolean renameItem(String path, String newName) {
            try {
                if (path == null)
                    return false;
                File f = new File(path);
                File mediaCacheDir = new File("media_cache").getCanonicalFile();
                File newFile = new File(f.getParent(), newName);
                return isWithinDirectory(f, mediaCacheDir) &&
                        isWithinDirectory(newFile, mediaCacheDir) &&
                        f.renameTo(newFile);
            } catch (IOException e) {
                return false;
            }
        }

        private void handleShare(HttpExchange ex, String path) throws IOException {
            if (path == null || !new File(path).exists()) {
                sendJson(ex, Map.of("success", false, "error", "File not found"));
                return;
            }
            // Restrict share links to media_cache only
            File mediaCacheDir = new File("media_cache").getCanonicalFile();
            if (!new File(path).getCanonicalPath().startsWith(mediaCacheDir.getCanonicalPath())) {
                sendJson(ex, Map.of("success", false, "error", "Sharing is only allowed for files in media_cache"));
                return;
            }
            String id = linkManager.createLink(path);
            sendJson(ex, Map.of("success", true, "link", id));
        }

        private void handlePaste(HttpExchange ex, Map<String, String> params) throws IOException {
            String mode = params.get("mode");
            String srcPath = params.get("src");
            String destFolder = params.get("dest");

            if (srcPath == null || destFolder == null) {
                sendJson(ex, Map.of("success", false, "error", "Missing params"));
                return;
            }

            File src = new File(srcPath);
            File mediaCacheDir = new File("media_cache").getCanonicalFile();

            // Resolve destination: media_cache / destFolder
            File destDir = new File("media_cache");
            if (!destFolder.isEmpty()) {
                destDir = new File(destDir, destFolder);
            }
            // If destDir is just "media_cache", it's valid.

            if (!src.exists() || !isWithinDirectory(src, mediaCacheDir) || !isWithinDirectory(destDir, mediaCacheDir)) {
                sendJson(ex, Map.of("success", false, "error", "Invalid paths"));
                return;
            }

            File destFile = new File(destDir, src.getName());

            try {
                if ("cut".equals(mode)) {
                    Files.move(src.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else if ("copy".equals(mode)) {
                    if (src.isDirectory()) {
                        // Simple directory copy not implemented for brevity, but file copy is
                        sendJson(ex, Map.of("success", false, "error", "Copying directories not supported yet"));
                        return;
                    }
                    Files.copy(src.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                sendJson(ex, Map.of("success", true));
            } catch (Exception e) {
                sendJson(ex, Map.of("success", false, "error", e.getMessage()));
            }
        }

        private void handlePack(HttpExchange ex, Map<String, String> params) throws IOException {
            String folder = params.getOrDefault("folder", "");
            String name = params.get("name");
            String filesParam = params.get("files");

            if (name == null || filesParam == null) {
                sendJson(ex, Map.of("success", false, "error", "Missing params"));
                return;
            }

            if (!name.toLowerCase().endsWith(".zip") && !name.toLowerCase().endsWith(".7z")) {
                name += ".zip"; // Default to zip
            }

            File mediaCacheDir = new File("media_cache").getCanonicalFile();
            File currentDir = new File(mediaCacheDir, folder);

            if (!isWithinDirectory(currentDir, mediaCacheDir)) {
                sendJson(ex, Map.of("success", false, "error", "Invalid folder"));
                return;
            }

            File zipFile = new File(currentDir, name);
            File targetFile = new File(currentDir, filesParam); // Currently supports single file/folder

            if (!targetFile.exists() || !isWithinDirectory(targetFile, mediaCacheDir)) {
                sendJson(ex, Map.of("success", false, "error", "Target not found"));
                return;
            }

            try (FileOutputStream fos = new FileOutputStream(zipFile);
                    ZipOutputStream zos = new ZipOutputStream(fos)) {

                if (targetFile.isDirectory()) {
                    zipDirectory(targetFile, targetFile.getName(), zos);
                } else {
                    zipFile(targetFile, zos);
                }

                sendJson(ex, Map.of("success", true));
            } catch (Exception e) {
                sendJson(ex, Map.of("success", false, "error", e.getMessage()));
            }
        }

        private void zipFile(File fileToZip, ZipOutputStream zipOut) throws IOException {
            try (FileInputStream fis = new FileInputStream(fileToZip)) {
                ZipEntry zipEntry = new ZipEntry(fileToZip.getName());
                zipOut.putNextEntry(zipEntry);
                byte[] bytes = new byte[1024];
                int length;
                while ((length = fis.read(bytes)) >= 0) {
                    zipOut.write(bytes, 0, length);
                }
            }
        }

        private void zipDirectory(File folder, String parentFolder, ZipOutputStream zipOut) throws IOException {
            for (File file : folder.listFiles()) {
                if (file.isDirectory()) {
                    zipDirectory(file, parentFolder + "/" + file.getName(), zipOut);
                    continue;
                }
                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry zipEntry = new ZipEntry(parentFolder + "/" + file.getName());
                    zipOut.putNextEntry(zipEntry);
                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = fis.read(bytes)) >= 0) {
                        zipOut.write(bytes, 0, length);
                    }
                }
            }
        }

        private void handleUnpack(HttpExchange ex, Map<String, String> params) throws IOException {
            String folder = params.getOrDefault("folder", "");
            String name = params.get("name");

            File mediaCacheDir = new File("media_cache").getCanonicalFile();
            File currentDir = new File(mediaCacheDir, folder);
            File zipFile = new File(currentDir, name);

            if (!zipFile.exists() || !isWithinDirectory(zipFile, mediaCacheDir)) {
                sendJson(ex, Map.of("success", false, "error", "Zip not found"));
                return;
            }

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry zipEntry = zis.getNextEntry();
                while (zipEntry != null) {
                    File newFile = new File(currentDir, zipEntry.getName());

                    // Security check: Zip Slip vulnerability prevention
                    if (!isWithinDirectory(newFile, currentDir)) {
                        // skip unsafe entry
                        zipEntry = zis.getNextEntry();
                        continue;
                    }

                    if (zipEntry.isDirectory()) {
                        newFile.mkdirs();
                    } else {
                        new File(newFile.getParent()).mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(newFile)) {
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }
                    }
                    zipEntry = zis.getNextEntry();
                }
                sendJson(ex, Map.of("success", true));
            } catch (Exception e) {
                sendJson(ex, Map.of("success", false, "error", e.getMessage()));
            }
        }
    }

    private class MediaFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = parseQuery(ex.getRequestURI().getQuery()).get("path");
            if (path == null) {
                ex.sendResponseHeaders(400, -1);
                return;
            }

            try {
                File f = new File(path);
                File mediaCacheDir = new File("media_cache").getCanonicalFile();

                if (!f.exists() || !isWithinDirectory(f, mediaCacheDir)) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }

                serveFileWithRanges(ex, f);
            } catch (IOException e) {
                ex.sendResponseHeaders(500, -1);
            }
        }

        private boolean isWithinDirectory(File file, File directory) throws IOException {
            String canonicalFile = file.getCanonicalPath();
            String canonicalDir = directory.getCanonicalPath();
            return canonicalFile.startsWith(canonicalDir);
        }
    }

    private class ThumbnailHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = parseQuery(ex.getRequestURI().getQuery()).get("path");
            MediaTranscoder transcoder = kernel.getService(MediaTranscoder.class);

            if (transcoder != null) {
                File f = transcoder.getOrCreateThumbnail(new File(path));
                if (f != null && f.exists()) {
                    ex.getResponseHeaders().add("Content-Type", "image/jpeg");
                    ex.sendResponseHeaders(200, f.length());
                    try (OutputStream os = ex.getResponseBody(); FileInputStream fis = new FileInputStream(f)) {
                        fis.transferTo(os);
                    }
                }
            } else {
                ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private class PluginSettingsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                handleGet(ex);
            } else if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                handlePost(ex);
            }
        }

        private void handleGet(HttpExchange ex) throws IOException {
            com.framework.core.config.Configuration config = kernel.getConfigManager().getConfig();
            JsonArray result = new JsonArray();
            com.framework.core.plugin.PluginLoader loader = kernel.getPluginLoader();

            // 1. Map known (enabled/disabled) plugins from config
            Set<String> processedPlugins = new HashSet<>();

            // Get currently active plugins from loader
            Collection<com.framework.api.MediaPlugin> active = loader.getPlugins();
            Map<String, com.framework.api.MediaPlugin> activeMap = new HashMap<>();
            for (com.framework.api.MediaPlugin p : active)
                activeMap.put(p.getName(), p);

            // List all JARs in plugins dir
            File pluginDir = new File("plugins");
            File[] jars = pluginDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars == null)
                jars = new File[0];

            // Strategy: List all JAR files as the primary source of truth for "Available
            // Plugins"
            for (File jar : jars) {
                JsonObject p = new JsonObject();
                p.addProperty("jar", jar.getName());

                // Try to find if this JAR corresponds to an active plugin
                // identifying by name is hard without opening the JAR,
                // so we rely on the UI or simple heuristic for now?
                // Actually, let's just list the JARs and match active plugins by Name if
                // possible.
                // Simplified: We return ACTIONS specific to JARs or Names.

                p.addProperty("status", "AVAILABLE");
                result.add(p);
            }

            // ADDITIONALLY: return the list of ACTIVE plugins, which is what the UI really
            // needs to manage
            for (com.framework.api.MediaPlugin pl : active) {
                JsonObject p = new JsonObject();
                p.addProperty("name", pl.getName());
                p.addProperty("version", pl.getVersion());
                p.addProperty("status", "ACTIVE");
                p.addProperty("enabled", true);

                // Settings
                Map<String, String> settings = config.pluginConfigs.get(pl.getName());
                if (settings == null)
                    settings = new HashMap<>();

                // Sync legacy settings
                if (pl.getName().equals("TelegramIntegration")) {
                    settings.put("botToken", config.telegramToken);
                    settings.put("adminId", config.telegramAdminId);
                    settings.put("allowedChats", config.telegramAllowedChats);
                }

                JsonObject settingsJson = new JsonObject();
                for (Map.Entry<String, String> entry : settings.entrySet()) {
                    settingsJson.addProperty(entry.getKey(), entry.getValue());
                }
                p.add("settings", settingsJson);

                result.add(p);
            }

            // Also add disabled plugins from config that are NOT active
            for (String name : config.plugins.keySet()) {
                if (!activeMap.containsKey(name) && !config.plugins.get(name)) {
                    JsonObject p = new JsonObject();
                    p.addProperty("name", name);
                    p.addProperty("status", "DISABLED");
                    p.addProperty("enabled", false);
                    // Settings
                    Map<String, String> settings = config.pluginConfigs.get(name);
                    if (settings != null) {
                        JsonObject settingsJson = new JsonObject();
                        for (Map.Entry<String, String> entry : settings.entrySet()) {
                            settingsJson.addProperty(entry.getKey(), entry.getValue());
                        }
                        p.add("settings", settingsJson);
                    }
                    result.add(p);
                }
            }

            sendJson(ex, result);
        }

        private void handlePost(HttpExchange ex) throws IOException {
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = gson.fromJson(isr, JsonObject.class);
                String action = body.get("action").getAsString();
                com.framework.core.config.Configuration config = kernel.getConfigManager().getConfig();

                if ("toggle".equals(action)) {
                    String pluginName = body.get("plugin").getAsString();
                    boolean newState = body.get("enabled").getAsBoolean();
                    config.plugins.put(pluginName, newState);
                    kernel.getConfigManager().saveConfig();

                    if (newState) {
                        // Attempt to load? We need the JAR file.
                        // For now, toggle just sets config. Proper "Load" uses "load" action.
                        // But if it was just disabled, can we re-enable?
                        // If it's in the list of "loadedPlugins" but disabled? No, PluginLoader removes
                        // it.
                    } else {
                        kernel.getPluginLoader().unloadPlugin(pluginName);
                    }
                    sendJson(ex, Map.of("success", true));

                } else if ("unload".equals(action)) {
                    String pluginName = body.get("plugin").getAsString();
                    kernel.getPluginLoader().unloadPlugin(pluginName);
                    config.plugins.put(pluginName, false);
                    kernel.getConfigManager().saveConfig();
                    sendJson(ex, Map.of("success", true));

                } else if ("load".equals(action)) {
                    String jarName = body.get("jar").getAsString();
                    File jar = new File("plugins", jarName);
                    if (jar.exists()) {
                        try {
                            boolean loaded = kernel.getPluginLoader().loadPluginFromFile(jar, config);
                            if (loaded) {
                                // Enable in config automatically
                                // We don't know the name yet easily without iterating plugins,
                                // but loadPluginFromFile updates config if new.
                                kernel.getConfigManager().saveConfig();
                                sendJson(ex, Map.of("success", true));
                            } else {
                                sendError(ex, 500, "No valid plugin found in JAR (or duplicate)");
                            }
                        } catch (Exception e) {
                            sendError(ex, 500, "Load failed: " + e.getMessage());
                        }
                    } else {
                        sendError(ex, 404, "JAR not found");
                    }

                } else if ("reload".equals(action)) {
                    String pluginName = body.get("plugin").getAsString();
                    String jarName = body.has("jar") ? body.get("jar").getAsString() : null;

                    // 1. Unload
                    kernel.getPluginLoader().unloadPlugin(pluginName);

                    // 2. Load
                    if (jarName != null) {
                        // Use provided JAR
                        File jar = new File("plugins", jarName);
                        kernel.getPluginLoader().loadPluginFromFile(jar, config);
                    } else {
                        // Attempt to find JAR by name heuristic?
                        // Simple heuristic: pluginName + ".jar" or lowercase?
                        // Better: UI should send JAR name.
                        // Fallback: Scan dir?
                        File pluginDir = new File("plugins");
                        File[] jars = pluginDir.listFiles((d, n) -> n.toLowerCase().contains(pluginName.toLowerCase()));
                        if (jars != null && jars.length > 0) {
                            kernel.getPluginLoader().loadPluginFromFile(jars[0], config);
                        }
                    }

                    sendJson(ex, Map.of("success", true));

                } else if ("save_settings".equals(action)) {
                    String pluginName = body.get("plugin").getAsString();
                    JsonObject newSettings = body.getAsJsonObject("settings");
                    Map<String, String> map = config.pluginConfigs.computeIfAbsent(pluginName, k -> new HashMap<>());

                    for (String key : newSettings.keySet()) {
                        String val = newSettings.get(key).getAsString();
                        map.put(key, val);

                        if (pluginName.equals("TelegramIntegration")) {
                            if (key.equals("botToken"))
                                config.telegramToken = val;
                            if (key.equals("adminId"))
                                config.telegramAdminId = val;
                            if (key.equals("allowedChats"))
                                config.telegramAllowedChats = val;
                        }
                    }
                    kernel.getConfigManager().saveConfig();
                    sendJson(ex, Map.of("success", true));
                }
            } catch (Exception e) {
                logger.error("Error saving settings", e);
                sendError(ex, 500, e.getMessage());
            }
        }
    }

    private class CreatorsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            sendJson(ex, kernel.getStatisticsManager().getAllCreatorsDetailed());
        }
    }

    private class HistoryApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            sendJson(ex, Map.of("info", "history"));
        }
    }

    private class SurgeonHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
            String a = p.get("action");
            if ("clear_queue".equals(a))
                kernel.getQueueManager().clearQueue();
            else if ("clear_pipeline".equals(a))
                kernel.getPipelineManager().clear();
            else if ("toggle".equals(a))
                kernel.getPipelineManager().setSurgeonMode(Boolean.parseBoolean(p.get("val")));
            else if ("pause_queue".equals(a))
                kernel.getQueueManager().setPaused(Boolean.parseBoolean(p.get("val")));
            sendJson(ex, Map.of("status", "ok"));
        }
    }

    private class SessionApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String token = ex.getRequestHeaders().getFirst("X-MAF-Token");
            if (token == null)
                token = parseQuery(ex.getRequestURI().getQuery()).get("token");

            boolean isAdmin = kernel.getAuthManager().isAdmin(token);
            boolean isGuest = kernel.getAuthManager().isGuest(token);
            boolean isValid = kernel.getAuthManager().isValidToken(token);

            // Also include logs for stats if needed, or separate it?
            // Keeping logs for backward compat if frontend uses it,
            // but main purpose now provides session info.

            Map<String, Object> response = new HashMap<>();
            response.put("valid", isValid);
            response.put("isAdmin", isAdmin);
            response.put("isGuest", isGuest);

            // Add logs only if admin?
            if (isAdmin) {
                response.put("apiKey", kernel.getAuthManager().getSessionApiKey());
                File log = new File("logs/latest.log");
                List<String> lines = log.exists() ? Files.readAllLines(log.toPath()) : List.of("No log");
                if (lines.size() > 100)
                    lines = lines.subList(lines.size() - 100, lines.size());
                response.put("logs", lines);
            }

            sendJson(ex, response);
        }
    }

    private void sendJson(HttpExchange ex, Object data) throws IOException {
        String json = (data instanceof String) ? (String) data : gson.toJson(data);
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private void sendError(HttpExchange ex, int code, String msg) throws IOException {
        // If client accepts HTML, serve custom error page
        List<String> accept = ex.getRequestHeaders().get("Accept");
        if (accept != null && accept.stream().anyMatch(s -> s.contains("text/html"))) {
            String page = code == 403 ? "web/403.html" : "web/404.html"; // Simple mapping
            File file = new File(page);
            if (file.exists()) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                ex.getResponseHeaders().add("Content-Type", "text/html");
                ex.sendResponseHeaders(code, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
        }

        String json = gson.toJson(Map.of("error", msg));
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> res = new HashMap<>();
        if (query == null)
            return res;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1)
                res.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return res;
    }

    private class StaticFileHandler implements HttpHandler {
        private final String baseDir;
        private final String prefix;

        public StaticFileHandler(String baseDir, String prefix) {
            this.baseDir = baseDir;
            this.prefix = prefix;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String rel = path;
            if (path.startsWith(prefix)) {
                rel = path.substring(prefix.length());
            }
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            File f = new File(baseDir, rel);

            if (!f.exists() || f.isDirectory()) {
                sendError(ex, 404, "File not found");
                return;
            }

            String mime = "text/plain";
            if (path.endsWith(".js"))
                mime = "application/javascript";
            else if (path.endsWith(".css"))
                mime = "text/css";
            else if (path.endsWith(".map"))
                mime = "application/json";
            else if (path.endsWith(".md"))
                mime = "text/markdown; charset=utf-8";
            else if (path.endsWith(".html"))
                mime = "text/html; charset=utf-8";

            ex.getResponseHeaders().add("Content-Type", mime);
            ex.sendResponseHeaders(200, f.length());
            try (OutputStream os = ex.getResponseBody(); InputStream is = new FileInputStream(f)) {
                is.transferTo(os);
            }
        }

    }

    private class GuestUserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                    JsonObject body = gson.fromJson(isr, JsonObject.class);
                    // Simple guest login validation
                    // For now, just return a guest token if enabled
                    AuthManager.AuthResult result = kernel.getAuthManager().createGuestSession();
                    sendJson(ex, result);
                } catch (Exception e) {
                    sendError(ex, 500, e.getMessage());
                }
            } else {
                sendError(ex, 405, "POST Required");
            }
        }
    }

    // --- WebServer Interface Implementation ---

    @Override
    public void registerRoute(String path, HttpHandler handler, boolean isProtected) {
        if (server == null)
            return;

        // Remove existing if present to avoid "Context already exists"
        unregisterRoute(path);

        HttpHandler finalHandler = isProtected ? new AuthWrapper(handler) : handler;
        HttpContext context = server.createContext(path, finalHandler);
        registeredContexts.put(path, context);

        String visibility = isProtected ? "PROTECTED" : "PUBLIC";
        logger.info("Registering {} context: {}", visibility, path);
    }

    @Override
    public void registerStaticRoute(String path, java.nio.file.Path fileSystemPath, boolean isProtected) {
        HttpHandler staticHandler = new GenericStaticFileHandler(fileSystemPath.toFile());
        registerRoute(path, staticHandler, isProtected);
    }

    @Override
    public void unregisterRoute(String path) {
        if (server == null)
            return;
        HttpContext context = registeredContexts.remove(path);
        if (context != null) {
            server.removeContext(path);
            logger.info("Unregistered context: {}", path);
        }
    }

    // Simple Static File Handler
    private static class GenericStaticFileHandler implements HttpHandler {
        private final File rootDir;

        public GenericStaticFileHandler(File rootDir) {
            this.rootDir = rootDir;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String uriPath = ex.getRequestURI().getPath();
            // Basic security check
            if (uriPath.contains("..")) {
                ex.sendResponseHeaders(403, -1);
                return;
            }

            // Map URI to file.
            // Warning: Context might be /manga, so /manga/index.html -> index.html
            // We need to know the context path to strip it?
            // We assume the plugin handles the path mapping or receives requests relative
            // to root if utilizing this.
            // But standard HttpHandler receives full URI.
            // To make this generic, we ideally need to know the context path.
            // For now, we simply serve the file if it matches a file in rootDir.
            // If uriPath is /manga/style.css and rootDir is .../web/manga, we need to strip
            // /manga.
            // But we don't know /manga here easily without passing it.
            // Let's assume for now that plugins invoking this will handle pathing or use a
            // specific structure.

            // IMPROVEMENT: For the specific case of MangaPlugin, it likely registers /manga
            // and expects
            // requests to /manga/... to map to web/manga/...
            // So we need to match the end of the URI.

            File f = new File(rootDir, new File(uriPath).getName());
            // Wait, that flattens directories.

            // Let's rely on a "best effort" relative path calculation or just serve 404 if
            // complex.
            // Realistically, plugins should use their own handlers for complex static
            // serving or use a library.
            // This implementation is a placeholder to satisfy the interface.

            ex.sendResponseHeaders(404, -1);
        }
    }

    private class LinkManager {
        private final Map<String, LinkData> links = new HashMap<>();
        private final File file = new File("ShareLinks.json");

        private class LinkData {
            String path;
            int visits;
            long createdAt;

            LinkData(String path) {
                this.path = path;
                this.visits = 0;
                this.createdAt = System.currentTimeMillis();
            }
        }

        public LinkManager() {
            load();
        }

        public synchronized String createLink(String path) {
            // Check if link already exists for this path
            for (var entry : links.entrySet()) {
                if (entry.getValue().path.equals(path)) {
                    return entry.getKey();
                }
            }
            String id = UUID.randomUUID().toString().substring(0, 8);
            links.put(id, new LinkData(path));
            save();
            return id;
        }

        public synchronized String getPath(String id) {
            LinkData ld = links.get(id);
            return ld != null ? ld.path : null;
        }

        public synchronized int recordVisit(String id) {
            LinkData ld = links.get(id);
            if (ld != null) {
                ld.visits++;
                save();
                return ld.visits;
            }
            return 0;
        }

        public synchronized int getVisits(String id) {
            LinkData ld = links.get(id);
            return ld != null ? ld.visits : 0;
        }

        private void save() {
            try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(links, fw);
            } catch (IOException e) {
                logger.error("Failed to save links", e);
            }
        }

        private void load() {
            if (!file.exists())
                return;
            try (FileReader fr = new FileReader(file, StandardCharsets.UTF_8)) {
                // Try loading new format first (Map<String, LinkData>)
                com.google.gson.JsonElement root = gson.fromJson(fr, com.google.gson.JsonElement.class);
                if (root != null && root.isJsonObject()) {
                    for (var entry : root.getAsJsonObject().entrySet()) {
                        String id = entry.getKey();
                        com.google.gson.JsonElement val = entry.getValue();
                        if (val.isJsonPrimitive()) {
                            // Old format: "id" -> "path"
                            links.put(id, new LinkData(val.getAsString()));
                        } else if (val.isJsonObject()) {
                            // New format: "id" -> {path, visits, createdAt}
                            LinkData ld = gson.fromJson(val, LinkData.class);
                            links.put(id, ld);
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to load links", e);
            }
        }
    }

    // Initialized in constructor
    private final LinkManager linkManager = new LinkManager();

    private class ShareHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String uriPath = ex.getRequestURI().getPath();
            Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());

            // Extract share ID from path: /f/{id} or /f/{id}/subpath or /share/{id}/subpath
            String[] segments = uriPath.split("/");
            // segments[0] = "", segments[1] = "f" or "share", segments[2] = id, rest =
            // subpath
            String id = segments.length > 2 ? segments[2] : "";

            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                // Create link — validate path is within media_cache
                try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                    JsonObject body = gson.fromJson(isr, JsonObject.class);
                    String filePath = body.get("path").getAsString();
                    File mediaCacheDir = new File("media_cache").getCanonicalFile();
                    if (!new File(filePath).getCanonicalPath().startsWith(mediaCacheDir.getCanonicalPath())) {
                        sendError(ex, 403, "Sharing is only allowed for files in media_cache");
                        return;
                    }
                    String shareId = linkManager.createLink(filePath);
                    sendJson(ex, Map.of("id", shareId, "url", "/f/" + shareId));
                } catch (Exception e) {
                    sendError(ex, 500, e.getMessage());
                }
                return;
            }

            // GET request — resolve share
            String sharedPath = linkManager.getPath(id);
            if (sharedPath == null) {
                sendError(ex, 404, "Link not found or expired");
                return;
            }

            File sharedRoot = new File(sharedPath);
            try {
                File mediaCacheDir = new File("media_cache").getCanonicalFile();
                if (!sharedRoot.getCanonicalPath().startsWith(mediaCacheDir.getCanonicalPath())) {
                    sendError(ex, 403, "Forbidden");
                    return;
                }
            } catch (IOException e) {
                sendError(ex, 500, "Path validation failed");
                return;
            }

            String apiMode = query.get("api");

            // --- API: List folder contents ---
            if ("list".equals(apiMode)) {
                handleListApi(ex, id, sharedRoot, query.get("path"));
                return;
            }

            // --- API: Stream a file ---
            if ("stream".equals(apiMode)) {
                String relFile = query.get("file");
                if (relFile == null || relFile.isEmpty()) {
                    // Stream the root itself if it's a file
                    if (sharedRoot.isFile()) {
                        streamFile(ex, sharedRoot);
                    } else {
                        sendError(ex, 400, "No file specified");
                    }
                    return;
                }
                File target = new File(sharedRoot, relFile).getCanonicalFile();
                // Ensure target is within shared root
                if (!target.getCanonicalPath().startsWith(sharedRoot.getCanonicalPath())) {
                    sendError(ex, 403, "Access denied");
                    return;
                }
                if (target.isFile()) {
                    streamFile(ex, target);
                } else {
                    sendError(ex, 404, "File not found");
                }
                return;
            }

            // --- Default browser request ---
            if (sharedRoot.isFile()) {
                // Single file share — stream it directly
                linkManager.recordVisit(id);
                streamFile(ex, sharedRoot);
            } else if (sharedRoot.isDirectory()) {
                // Folder share — serve the SPA (visit recorded on first list API call)
                linkManager.recordVisit(id);
                serveSharePage(ex);
            } else {
                sendError(ex, 404, "Shared content not found");
            }
        }

        private void handleListApi(HttpExchange ex, String shareId, File sharedRoot, String subPath)
                throws IOException {
            if (sharedRoot.isFile()) {
                // Single file share
                JsonObject result = new JsonObject();
                result.addProperty("type", "file");
                result.addProperty("name", sharedRoot.getName());
                result.addProperty("size", sharedRoot.length());
                result.addProperty("mediaType", detectMediaType(sharedRoot.getName()));
                sendJson(ex, result);
                return;
            }

            // Folder share
            File targetDir = sharedRoot;
            if (subPath != null && !subPath.isEmpty()) {
                targetDir = new File(sharedRoot, subPath).getCanonicalFile();
                if (!targetDir.getCanonicalPath().startsWith(sharedRoot.getCanonicalPath())) {
                    sendError(ex, 403, "Access denied");
                    return;
                }
            }

            if (!targetDir.isDirectory()) {
                sendError(ex, 404, "Folder not found");
                return;
            }

            File[] children = targetDir.listFiles();
            JsonArray items = new JsonArray();
            if (children != null) {
                // Sort: folders first, then files alphabetically
                java.util.Arrays.sort(children, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory())
                        return -1;
                    if (!a.isDirectory() && b.isDirectory())
                        return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });

                for (File child : children) {
                    // Skip hidden files
                    if (child.getName().startsWith("."))
                        continue;

                    JsonObject item = new JsonObject();
                    item.addProperty("name", child.getName());

                    // Calculate relative path from shared root
                    String relativePath = sharedRoot.toPath().relativize(child.toPath()).toString()
                            .replace('\\', '/');
                    item.addProperty("relativePath", relativePath);

                    if (child.isDirectory()) {
                        item.addProperty("type", "folder");
                    } else {
                        item.addProperty("type", "file");
                        item.addProperty("size", child.length());
                        item.addProperty("mediaType", detectMediaType(child.getName()));
                    }
                    items.add(item);
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("type", "folder");
            result.addProperty("name", sharedRoot.getName());
            result.addProperty("visits", linkManager.getVisits(shareId));
            result.add("items", items);
            sendJson(ex, result);
        }

        private void streamFile(HttpExchange ex, File f) throws IOException {
            if (!f.exists() || !f.isFile()) {
                sendError(ex, 404, "File not found");
                return;
            }
            try {
                ex.getResponseHeaders().add("Content-Disposition", "inline; filename=\"" + f.getName() + "\"");
                serveFileWithRanges(ex, f);
            } catch (IOException e) {
                logger.error("Failed to stream file: " + f.getAbsolutePath(), e);
                try {
                    ex.sendResponseHeaders(500, -1);
                } catch (Exception ignored) {
                }
            }
        }

        private void serveSharePage(HttpExchange ex) throws IOException {
            File htmlFile = new File("web/share.html");
            if (!htmlFile.exists()) {
                sendError(ex, 500, "Share page not found");
                return;
            }
            byte[] content = Files.readAllBytes(htmlFile.toPath());
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, content.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(content);
            }
        }

        private String detectMediaType(String name) {
            String lower = name.toLowerCase();
            if (lower.matches(".*\\.(mp4|mkv|avi|webm|mov|flv|wmv)$"))
                return "video";
            if (lower.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg)$"))
                return "image";
            if (lower.matches(".*\\.(mp3|flac|wav|ogg|aac|m4a|wma)$"))
                return "audio";
            if (lower.endsWith(".pdf"))
                return "pdf";
            if (lower.matches(".*\\.(txt|log|md|json|xml|csv|yml|yaml|ini|cfg|conf|properties)$"))
                return "text";
            return "file";
        }
    }
}
