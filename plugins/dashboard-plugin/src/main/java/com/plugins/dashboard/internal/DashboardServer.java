package com.plugins.dashboard.internal;

import com.plugins.coremedia.TranscoderService;
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
import java.util.*;

public class DashboardServer {
    private static final Logger logger = LoggerFactory.getLogger(DashboardServer.class);
    private final Kernel kernel;
    private HttpServer server;
    private final Gson gson = new Gson();
    private static final int PORT = 6875;

    public DashboardServer(Kernel kernel) {
        this.kernel = kernel;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);

            // --- Auth APIs ---
            server.createContext("/api/login", new LoginHandler());
            server.createContext("/api/logout", new LogoutHandler());
            server.createContext("/login", new HtmlHandler("web/login.html")); // Must NOT be wrapped in AuthWrapper!

            // --- Web Views (HTML) ---
            // These must be PUBLIC so the browser can load the "App Shell".
            // The JavaScript on these pages will then use the token to fetch protected
            // data.
            server.createContext("/status", new HtmlHandler("web/status.html"));
            server.createContext("/media", new HtmlHandler("web/media_manager.html"));
            server.createContext("/members", new HtmlHandler("web/members.html"));
            server.createContext("/commands", new HtmlHandler("web/commands.html"));
            server.createContext("/settings", new HtmlHandler("web/settings.html")); // Added missing settings page

            // --- Static Assets (JS, CSS) ---
            // Must NOT be wrapped in AuthWrapper to allow public access
            server.createContext("/js", new StaticFileHandler("web/js", "/js"));
            server.createContext("/js", new StaticFileHandler("web/js", "/js"));
            server.createContext("/css", new StaticFileHandler("web/css", "/css"));

            // Share Handler (Public)
            server.createContext("/share", new ShareHandler());

            // --- Data APIs ---
            // These remain PROTECTED by AuthWrapper
            createContext("/api/status", new ApiStatusHandler());
            createContext("/api/guest", new GuestUserHandler());
            createContext("/api/media", new MediaApiHandler());
            createContext("/api/history", new HistoryApiHandler());
            createContext("/api/surgeon", new SurgeonHandler());
            createContext("/api/creators", new CreatorsApiHandler());
            createContext("/api/session", new SessionApiHandler());
            createContext("/api/settings", new SettingsHandler());
            createContext("/api/members", new MembersApiHandler());
            createContext("/api/plugins", new PluginSettingsHandler());

            // --- Action APIs ---
            createContext("/api/command", new CommandApiHandler());
            createContext("/push", new PushHandler());
            createContext("/api/torrent", new TorrentPushHandler());

            // --- Streaming & Files ---
            createContext("/media/file", new MediaFileHandler());
            createContext("/media/thumb", new ThumbnailHandler());

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            server.start();
            logger.info("Dashboard Server running on port {}", PORT);
        } catch (IOException e) {
            logger.error("Failed to start WebServer", e);
        }
    }

    private void createContext(String path, HttpHandler handler) {
        server.createContext(path, new AuthWrapper(handler));
    }

    public void stop() {
        if (server != null)
            server.stop(0);
    }

    // =================================================================================
    // HANDLERS
    // =================================================================================

    private class AuthWrapper implements HttpHandler {
        private final HttpHandler inner;

        public AuthWrapper(HttpHandler inner) {
            this.inner = inner;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String token = ex.getRequestHeaders().getFirst("X-MAF-Token");
            if (token == null) {
                // Try from query for downloads/previews
                token = parseQuery(ex.getRequestURI().getQuery()).get("token");
            }

            if (kernel.getAuthManager().isValidToken(token)) {
                inner.handle(ex);
            } else {
                if (ex.getRequestURI().getPath().startsWith("/api/") || ex.getRequestURI().getPath().equals("/push")) {
                    sendError(ex, 401, "Unauthorized");
                } else {
                    // Redirect HTML pages to login
                    ex.getResponseHeaders().add("Location", "/login");
                    ex.sendResponseHeaders(302, -1);
                }
            }
        }
    }

    private class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "POST expected");
                return;
            }

            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = gson.fromJson(isr, JsonObject.class);
                String user = body.get("user").getAsString();
                String pass = body.get("pass").getAsString();

                AuthManager.AuthResult result = kernel.getAuthManager().createSession(user, pass);
                sendJson(ex, result);
            } catch (Exception e) {
                sendError(ex, 400, "Bad Request");
            }
        }
    }

    private class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            // Simply return OK, the client will delete the token
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
            } else if (method.equalsIgnoreCase("POST")) {
                if ("upload".equals(action))
                    handleUpload(ex, params.getOrDefault("folder", ""));
                else if ("save_text".equals(action))
                    handleSaveText(ex);
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
                return isWithinDirectory(f, mediaCacheDir) && f.delete();
            } catch (IOException e) {
                return false;
            }
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

                String mime = Files.probeContentType(f.toPath());
                ex.getResponseHeaders().add("Content-Type", mime != null ? mime : "application/octet-stream");
                ex.sendResponseHeaders(200, f.length());
                try (OutputStream os = ex.getResponseBody(); FileInputStream fis = new FileInputStream(f)) {
                    fis.transferTo(os);
                }
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
            TranscoderService transcoder = kernel.getService(TranscoderService.class);

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

    // 4. Plugin Management & Settings Handler
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

            // Wir iterieren über ALLE bekannten Plugins (aus der Config Map)
            // und fügen Status und Settings hinzu.
            for (String pluginName : config.plugins.keySet()) {
                JsonObject p = new JsonObject();
                p.addProperty("name", pluginName);
                p.addProperty("enabled", config.plugins.get(pluginName));

                // Settings holen
                Map<String, String> settings = config.pluginConfigs.get(pluginName);
                if (settings == null)
                    settings = new HashMap<>();

                // Spezielle Logik für Telegram: Globale Config-Felder einmischen
                if (pluginName.equals("TelegramIntegration")) {
                    settings.put("botToken", config.telegramToken);
                    settings.put("adminId", config.telegramAdminId);
                    settings.put("allowedChats", config.telegramAllowedChats);
                }

                // Settings als JSON Objekt hinzufügen
                JsonObject settingsJson = new JsonObject();
                for (Map.Entry<String, String> entry : settings.entrySet()) {
                    settingsJson.addProperty(entry.getKey(), entry.getValue());
                }
                p.add("settings", settingsJson);

                result.add(p);
            }
            sendJson(ex, result);
        }

        private void handlePost(HttpExchange ex) throws IOException {
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = gson.fromJson(isr, JsonObject.class);
                String action = body.get("action").getAsString();
                String pluginName = body.get("plugin").getAsString();
                com.framework.core.config.Configuration config = kernel.getConfigManager().getConfig();

                if ("toggle".equals(action)) {
                    boolean newState = body.get("enabled").getAsBoolean();
                    config.plugins.put(pluginName, newState);
                    logger.info("Plugin {} toggled to {}. Restart required.", pluginName, newState);
                } else if ("save_settings".equals(action)) {
                    JsonObject newSettings = body.getAsJsonObject("settings");
                    Map<String, String> map = config.pluginConfigs.computeIfAbsent(pluginName, k -> new HashMap<>());

                    for (String key : newSettings.keySet()) {
                        String val = newSettings.get(key).getAsString();
                        map.put(key, val);

                        // Sync Backwards für Telegram (damit der Core es auch versteht, falls wir noch
                        // alte Logik nutzen)
                        if (pluginName.equals("TelegramIntegration")) {
                            if (key.equals("botToken"))
                                config.telegramToken = val;
                            if (key.equals("adminId"))
                                config.telegramAdminId = val;
                            if (key.equals("allowedChats"))
                                config.telegramAllowedChats = val;
                        }
                    }
                }

                kernel.getConfigManager().saveConfig();
                sendJson(ex, Map.of("success", true, "requiresRestart", "toggle".equals(action)));
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
            File log = new File("logs/latest.log");
            List<String> lines = log.exists() ? Files.readAllLines(log.toPath()) : List.of("No log");
            if (lines.size() > 100)
                lines = lines.subList(lines.size() - 100, lines.size());
            sendJson(ex, Map.of("logs", lines));
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

    private class LinkManager {
        private final Map<String, String> links = new HashMap<>();
        private final File file = new File("ShareLinks.json");

        public LinkManager() {
            load();
        }

        public synchronized String createLink(String path) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            links.put(id, path);
            save();
            return id;
        }

        public synchronized String getPath(String id) {
            return links.get(id);
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
                Map<String, String> data = gson.fromJson(fr,
                        new com.google.gson.reflect.TypeToken<Map<String, String>>() {
                        }.getType());
                if (data != null)
                    links.putAll(data);
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
            String path = ex.getRequestURI().getPath();
            // /share/ID
            String id = path.substring(path.lastIndexOf('/') + 1);

            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                // Create link
                try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                    JsonObject body = gson.fromJson(isr, JsonObject.class);
                    String filePath = body.get("path").getAsString();
                    String shareId = linkManager.createLink(filePath);
                    sendJson(ex, Map.of("id", shareId, "url", "/share/" + shareId));
                } catch (Exception e) {
                    sendError(ex, 500, e.getMessage());
                }
                return;
            }

            // GET - Serve file
            String filePath = linkManager.getPath(id);
            if (filePath != null) {
                File f = new File(filePath);
                if (f.exists()) {
                    String mime = Files.probeContentType(f.toPath());
                    ex.getResponseHeaders().add("Content-Type", mime != null ? mime : "application/octet-stream");
                    ex.sendResponseHeaders(200, f.length());
                    try (OutputStream os = ex.getResponseBody(); FileInputStream fis = new FileInputStream(f)) {
                        fis.transferTo(os);
                    }
                    return;
                }
            }
            sendError(ex, 404, "Link not found or expired");
        }
    }
}