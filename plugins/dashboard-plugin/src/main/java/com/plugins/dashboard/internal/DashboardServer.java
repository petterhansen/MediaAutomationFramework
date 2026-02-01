package internal;

import com.plugins.coremedia.TranscoderService;
import com.framework.common.auth.UserManager;
import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import com.framework.services.stats.StatisticsManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.BasicAuthenticator;
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

            BasicAuthenticator auth = new BasicAuthenticator("MediaAutomation Protected") {
                @Override
                public boolean checkCredentials(String user, String pwd) {
                    return kernel.getAuthManager().checkWebCredentials(user, pwd);
                }
            };

            // --- Web Views (HTML) ---
            createContext("/status", new HtmlHandler("web/status.html"), auth);
            createContext("/media", new HtmlHandler("web/media_manager.html"), auth);
            createContext("/members", new HtmlHandler("web/members.html"), auth);
            // commands.html wird meist client-seitig geladen, aber falls wir es server-side brauchen:
            createContext("/commands", new HtmlHandler("web/commands.html"), auth);

            // --- Data APIs ---
            createContext("/api/status", new ApiStatusHandler(), auth);
            createContext("/api/media", new MediaApiHandler(), auth);
            createContext("/api/history", new HistoryApiHandler(), auth);
            createContext("/api/surgeon", new SurgeonHandler(), auth);
            createContext("/api/creators", new CreatorsApiHandler(), auth);
            createContext("/api/session", new SessionApiHandler(), auth);
            createContext("/api/settings", new SettingsHandler(), auth);
            createContext("/api/members", new MembersApiHandler(), auth);

            // --- Action APIs ---
            createContext("/api/command", new CommandApiHandler(), auth); // NEU: Befehle ausführen
            createContext("/push", new PushHandler(), auth); // NEU: Browser Dump empfangen
            createContext("/api/torrent", new TorrentPushHandler(), auth); // NEU: Magnet Links

            // --- Streaming & Files ---
            createContext("/media/file", new MediaFileHandler(), auth);
            createContext("/media/thumb", new ThumbnailHandler(), auth);

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            server.start();
            logger.info("Dashboard Server running on port {}", PORT);
        } catch (IOException e) {
            logger.error("Failed to start WebServer", e);
        }
    }

    private void createContext(String path, HttpHandler handler, BasicAuthenticator auth) {
        server.createContext(path, handler).setAuthenticator(auth);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // =================================================================================
    //   NEUE & ECHTE HANDLER (Keine Dummies mehr)
    // =================================================================================

    // 1. Command API (Verarbeitet /dl, /pause, etc. vom Web-Interface)
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
            if (line == null || line.trim().isEmpty()) return "Empty command";
            String[] parts = line.trim().split("\\s+");
            String cmd = parts[0].toLowerCase();

            // Direkte Verarbeitung ohne Telegram-Plugin-Abhängigkeit
            if (cmd.equals("/dl") || cmd.equals("dl")) {
                if (parts.length < 3) return "Syntax: /dl [Anzahl] [Suchbegriff]";
                try {
                    int amount = Integer.parseInt(parts[1]);
                    String query = parts[2];
                    QueueTask task = new QueueTask("SEARCH_BATCH");
                    task.addParameter("query", query);
                    task.addParameter("amount", amount);
                    task.addParameter("source", "party"); // Default Source
                    kernel.getQueueManager().addTask(task);
                    return "✅ Download gestartet: " + query + " (" + amount + ")";
                } catch (NumberFormatException e) { return "❌ Fehler: Anzahl muss eine Zahl sein."; }
            }

            if (cmd.equals("/r34") || cmd.equals("r34")) {
                String query = parts.length > 1 ? parts[1] : "all";
                QueueTask task = new QueueTask("BOORU_BATCH");
                task.addParameter("query", query);
                task.addParameter("amount", 10); // Default amount for quick add
                task.addParameter("source", "r34");
                kernel.getQueueManager().addTask(task);
                return "✅ R34 Suche gestartet: " + query;
            }

            if (cmd.equals("/pause")) {
                kernel.getQueueManager().setPaused(true);
                return "⏸️ Queue pausiert.";
            }
            if (cmd.equals("/resume")) {
                kernel.getQueueManager().setPaused(false);
                return "▶️ Queue läuft weiter.";
            }
            if (cmd.equals("/clean")) {
                kernel.getQueueManager().clearQueue();
                return "🗑️ Queue geleert.";
            }
            if (cmd.equals("/status")) {
                return "📊 Status: " + (kernel.getQueueManager().isPaused() ? "Paused" : "Running") +
                        " | Queue: " + kernel.getQueueManager().getQueueSize();
            }

            // Fallback: Versuche es an den Telegram Listener zu senden, falls aktiv
            if (kernel.getTelegramListener() != null) {
                // Wir simulieren keinen Chat-Kontext hier, das ist komplex.
                return "⚠️ Unbekannter Web-Befehl: " + cmd;
            }
            return "❌ Unbekannter Befehl.";
        }
    }

    // 2. Browser Push Handler (Echt)
    private class PushHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject json = gson.fromJson(isr, JsonObject.class);

                QueueTask task = new QueueTask("BROWSER_BATCH");

                // URLs extrahieren
                List<String> urls = new ArrayList<>();
                if (json.has("urls")) {
                    for (JsonElement e : json.getAsJsonArray("urls")) urls.add(e.getAsString());
                } else if (json.has("url")) {
                    urls.add(json.get("url").getAsString());
                }

                if (urls.isEmpty()) {
                    sendError(ex, 400, "No URLs provided");
                    return;
                }

                task.addParameter("urls", urls);
                task.addParameter("folder", json.has("folder") ? json.get("folder").getAsString() : "BrowserDump");
                if (json.has("cookies")) task.addParameter("cookies", json.get("cookies").getAsString());
                if (json.has("referer")) task.addParameter("referer", json.get("referer").getAsString());

                // Task absenden
                kernel.getQueueManager().addTask(task);

                sendJson(ex, Map.of("status", "ok", "count", urls.size()));
            } catch (Exception e) {
                logger.error("Push Error", e);
                sendError(ex, 500, "Internal Error");
            }
        }
    }

    // 3. Torrent Handler (Echt - nutzt DownloadService)
    private class TorrentPushHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject json = gson.fromJson(isr, JsonObject.class);
                String magnet = json.get("magnet").getAsString();

                // Wir nutzen hier einen Trick: Ein PipelineItem direkt submitten oder via QueueTask
                // Da Aria2 im DownloadService Magnet-Links kann, reicht ein normaler Download-Auftrag.

                QueueTask task = new QueueTask("DIRECT_DOWNLOAD");
                task.addParameter("url", magnet);
                task.addParameter("folder", "Torrents");

                // Wir erstellen ein PipelineItem direkt, um die Queue-Logik für "Sources" zu umgehen
                // oder wir registrieren eine "DirectSource".
                // Einfachste Lösung: Ein generisches PipelineItem in die Queue werfen via Helper-Source?
                // Da wir keine "DirectSource" haben, nutzen wir BrowserSource Logic missbräuchlich oder fügen es direkt ein.

                // Bessere Lösung: Wir erzeugen ein PipelineItem und werfen es direkt in den Manager.
                PipelineItem item = new PipelineItem(magnet, "Magnet_Upload_" + System.currentTimeMillis(), task);
                kernel.getPipelineManager().submit(item);

                sendJson(ex, Map.of("status", "queued", "type", "magnet"));
            } catch (Exception e) {
                sendError(ex, 500, e.getMessage());
            }
        }
    }

    // =================================================================================
    //   EXISTIERENDE HANDLER (Beibehalten & Optimiert)
    // =================================================================================

    private class SettingsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJson(ex, kernel.getConfigManager().getConfig());
            } else if (ex.getRequestMethod().equalsIgnoreCase("POST")) {
                try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                    com.framework.core.config.Configuration newConfig = gson.fromJson(isr, com.framework.core.config.Configuration.class);
                    if (newConfig != null) {
                        kernel.getConfigManager().updateConfig(newConfig);
                        sendJson(ex, Map.of("success", true));
                    } else sendError(ex, 400, "Bad Config");
                } catch (Exception e) { sendError(ex, 500, e.getMessage()); }
            }
        }
    }

    private class HtmlHandler implements HttpHandler {
        private final String path;
        public HtmlHandler(String path) { this.path = path; }
        @Override
        public void handle(HttpExchange ex) throws IOException {
            File f = new File(path);
            String resp = f.exists() ? Files.readString(f.toPath()) : "<h1>File not found: " + path + "</h1>";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        }
    }

    private class MembersApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
            String q = params.get("q");
            List<UserManager.UserData> users = kernel.getUserManager().searchUsers(q);
            JsonArray arr = new JsonArray();
            for(UserManager.UserData u : users) {
                JsonObject o = gson.toJsonTree(u).getAsJsonObject();
                String role = "USER";
                if(kernel.getAuthManager().isAdmin(u.id)) role = "ADMIN";
                else if(kernel.getAuthManager().isGuest(u.id)) role = "GUEST";
                o.addProperty("role", role);
                if (u.languageCode != null) o.addProperty("lang", u.languageCode);
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
            if (!history.has("net_dl")) history.add("net_dl", new JsonArray());
            if (!history.has("net_ul")) history.add("net_ul", new JsonArray());
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
                if ("list".equals(action)) sendJson(ex, scanDir(new File("media_cache")));
                else if ("delete".equals(action)) sendJson(ex, Map.of("success", deleteItem(params.get("path"))));
                else if ("rename".equals(action)) sendJson(ex, Map.of("success", renameItem(params.get("path"), params.get("newName"))));
                else if ("mkdir".equals(action)) sendJson(ex, Map.of("success", new File("media_cache", params.get("name")).mkdirs()));
                else if ("read_text".equals(action)) handleReadText(ex, params.get("path"));
            } else if (method.equalsIgnoreCase("POST")) {
                if ("upload".equals(action)) handleUpload(ex, params.getOrDefault("folder", ""));
                else if ("save_text".equals(action)) handleSaveText(ex);
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
                            if (f.isDirectory()) o.add("children", scanDir(f));
                            arr.add(o);
                        });
            }
            return arr;
        }

        private void handleReadText(HttpExchange ex, String path) throws IOException {
            File f = new File(path);
            if(f.exists() && f.getAbsolutePath().contains("media_cache")) sendJson(ex, Map.of("content", Files.readString(f.toPath(), StandardCharsets.UTF_8)));
            else sendError(ex, 404, "File not found");
        }
        private void handleSaveText(HttpExchange ex) throws IOException {
            try (InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = gson.fromJson(isr, JsonObject.class);
                File f = new File(body.get("path").getAsString());
                if(f.getAbsolutePath().contains("media_cache")) {
                    Files.writeString(f.toPath(), body.get("content").getAsString(), StandardCharsets.UTF_8);
                    sendJson(ex, Map.of("success", true));
                } else sendJson(ex, Map.of("success", false));
            } catch(Exception e) { sendJson(ex, Map.of("error", e.getMessage())); }
        }
        private void handleUpload(HttpExchange exchange, String folderName) throws IOException {
            try {
                DiskFileItemFactory factory = new DiskFileItemFactory();
                FileUpload upload = new FileUpload(factory);
                List<FileItem> items = upload.parseRequest(new HttpExchangeRequestContext(exchange));
                File targetDir = new File("media_cache");
                if (!folderName.isEmpty()) targetDir = new File(targetDir, folderName.replaceAll("[^a-zA-Z0-9._-]", "_"));
                if (!targetDir.exists()) targetDir.mkdirs();
                for (FileItem item : items) if (!item.isFormField()) item.write(new File(targetDir, new File(item.getName()).getName()));
                sendJson(exchange, Map.of("success", true));
            } catch (Exception e) { sendError(exchange, 500, "Upload failed"); }
        }
        private boolean deleteItem(String path) { return path != null && path.contains("media_cache") && new File(path).delete(); }
        private boolean renameItem(String path, String newName) { return path != null && path.contains("media_cache") && new File(path).renameTo(new File(new File(path).getParent(), newName)); }
    }

    private class MediaFileHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String path = parseQuery(ex.getRequestURI().getQuery()).get("path");
            if(path==null) { ex.sendResponseHeaders(400,-1); return; }
            File f = new File(path);
            if(!f.exists() || !f.getAbsolutePath().contains("media_cache")) { ex.sendResponseHeaders(404,-1); return; }
            String mime = Files.probeContentType(f.toPath());
            ex.getResponseHeaders().add("Content-Type", mime!=null?mime:"application/octet-stream");
            ex.sendResponseHeaders(200, f.length());
            try(OutputStream os=ex.getResponseBody(); FileInputStream fis=new FileInputStream(f)) { fis.transferTo(os); }
        }
    }

    private class ThumbnailHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String path = parseQuery(ex.getRequestURI().getQuery()).get("path");
            // 1. Service aus dem Kernel holen
            TranscoderService transcoder = kernel.getService(TranscoderService.class);

            if (transcoder != null) {
                File f = transcoder.getOrCreateThumbnail(new File(path));
                if(f!=null && f.exists()) {
                    ex.getResponseHeaders().add("Content-Type", "image/jpeg");
                    ex.sendResponseHeaders(200, f.length());
                    try(OutputStream os=ex.getResponseBody(); FileInputStream fis=new FileInputStream(f)) { fis.transferTo(os); }
                }
            } else {
                // CoreMedia Plugin ist nicht geladen
                ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private class CreatorsApiHandler implements HttpHandler { @Override public void handle(HttpExchange ex) throws IOException { sendJson(ex, kernel.getStatisticsManager().getAllCreatorsDetailed()); } }
    private class HistoryApiHandler implements HttpHandler { @Override public void handle(HttpExchange ex) throws IOException { sendJson(ex, Map.of("info","history")); } }
    private class SurgeonHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Map<String,String> p = parseQuery(ex.getRequestURI().getQuery());
            String a = p.get("action");
            if("clear_queue".equals(a)) kernel.getQueueManager().clearQueue();
            else if("clear_pipeline".equals(a)) kernel.getPipelineManager().clear();
            else if("toggle".equals(a)) kernel.getPipelineManager().setSurgeonMode(Boolean.parseBoolean(p.get("val")));
            else if("pause_queue".equals(a)) kernel.getQueueManager().setPaused(Boolean.parseBoolean(p.get("val")));
            sendJson(ex, Map.of("status","ok"));
        }
    }
    private class SessionApiHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            File log = new File("logs/latest.log");
            List<String> lines = log.exists() ? Files.readAllLines(log.toPath()) : List.of("No log");
            if(lines.size()>100) lines = lines.subList(lines.size()-100, lines.size());
            sendJson(ex, Map.of("logs", lines));
        }
    }

    private void sendJson(HttpExchange ex, Object data) throws IOException {
        String json = (data instanceof String) ? (String)data : gson.toJson(data);
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void sendError(HttpExchange ex, int code, String msg) throws IOException {
        String json = gson.toJson(Map.of("error", msg));
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> res = new HashMap<>();
        if (query == null) return res;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) res.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return res;
    }
}