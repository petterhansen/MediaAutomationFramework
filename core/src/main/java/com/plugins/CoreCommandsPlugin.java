package com.plugins;

import com.framework.api.MediaPlugin;
import com.framework.common.auth.UserManager;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class CoreCommandsPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(CoreCommandsPlugin.class);
    private Kernel kernel;
    private final long startTime = System.currentTimeMillis();

    @Override
    public String getName() { return "CoreCommands"; }

    @Override
    public String getVersion() { return "1.3.0"; }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;
        logger.info("🔌 CoreCommandsPlugin geladen. Registriere Befehle...");

        // Help & System
        kernel.registerCommand("/help", this::handleHelp);
        kernel.registerCommand("/start", this::handleHelp);
        kernel.registerCommand("/status", this::handleStatus);
        kernel.registerCommand("/stop", this::handleStop);

        // Queue Management
        kernel.registerCommand("/queue", this::handleQueue);
        kernel.registerCommand("/q", this::handleQueue);
        kernel.registerCommand("/pause", (id, c, a) -> {
            if (!isAdmin(id)) return;
            kernel.getQueueManager().setPaused(true);
            send(id, "⏸️ <b>Queue pausiert.</b>");
        });
        kernel.registerCommand("/resume", (id, c, a) -> {
            if (!isAdmin(id)) return;
            kernel.getQueueManager().setPaused(false);
            send(id, "▶️ <b>Queue läuft weiter.</b>");
        });

        // User Management
        kernel.registerCommand("/allow", this::handleAllow);
        kernel.registerCommand("/revoke", this::handleRevoke);
        kernel.registerCommand("/whois", this::handleWhois);

        // Tools
        kernel.registerCommand("/clean", this::handleClean);
        kernel.registerCommand("/log", this::handleLog);

        // --- SEARCH SHORTCUTS ---

        // Party Sources (Coomer/Kemono)
        kernel.registerCommand("/cm", (id, c, a) -> handleSearchShortcut(id, "coomer", a));
        kernel.registerCommand("/km", (id, c, a) -> handleSearchShortcut(id, "kemono", a));

        // Booru Sources (Rule34, XBooru, Safebooru)
        kernel.registerCommand("/r34", (id, c, a) -> handleBooruShortcut(id, "r34", a));
        kernel.registerCommand("/xb", (id, c, a) -> handleBooruShortcut(id, "xb", a));
        kernel.registerCommand("/sb", (id, c, a) -> handleBooruShortcut(id, "sb", a));
    }

    @Override
    public void onDisable() {
        logger.info("🔌 CoreCommandsPlugin deaktiviert.");
    }

    // --- HANDLER LOGIK ---

    private void handleHelp(long chatId, String cmd, String[] args) {
        StringBuilder sb = new StringBuilder("📚 <b>BEFEHLSÜBERSICHT</b>\n\n");

        sb.append("<b>🔍 Party Search (Coomer/Kemono):</b>\n");
        sb.append("<code>/cm [Name]</code> - Coomer (Leer = Popular)\n");
        sb.append("<code>/km [Name]</code> - Kemono (Leer = Popular)\n");

        sb.append("\n<b>🎨 Booru Search (Anime/Art):</b>\n");
        sb.append("<code>/r34 [Tags]</code> - Rule34 (Leer = Random)\n");
        sb.append("<code>/xb [Tags]</code> - XBooru (Leer = Random)\n");
        sb.append("<code>/sb [Tags]</code> - Safebooru (Leer = Random)\n");
        sb.append("<i>Beispiel: /r34 blue_hair elf</i>\n\n");

        sb.append("<b>ℹ️ Info & Queue:</b>\n");
        sb.append("<code>/status</code> - Systemstatus\n");
        sb.append("<code>/queue</code> - Warteschlange anzeigen\n");

        if (isAdmin(chatId)) {
            sb.append("\n<b>🛡️ Admin Tools:</b>\n");
            sb.append("<code>/pause</code>, <code>/resume</code>, <code>/stop</code>\n");
            sb.append("<code>/clean</code>, <code>/log</code>\n");
            sb.append("<code>/allow [ID]</code>, <code>/revoke [ID]</code>\n");
        }

        send(chatId, sb.toString());
    }

    // Handler für Coomer/Kemono (PartySource)
    private void handleSearchShortcut(long chatId, String source, String[] args) {
        String query = String.join(" ", args).trim();
        QueueTask task = new QueueTask("SEARCH_BATCH");
        // Wenn leer -> "all" für Popular Logic
        task.addParameter("query", query.isEmpty() ? "all" : query);
        task.addParameter("source", source);
        task.addParameter("chatId", chatId);
        task.addParameter("amount", 1); // Standard: 1 Post (kann mehrere Dateien enthalten)
        kernel.getQueueManager().addTask(task);

        String type = query.isEmpty() ? "🔥 Popular" : "🔍 Search";
        send(chatId, type + " gestartet: <b>" + source.toUpperCase() + "</b>\n" +
                (query.isEmpty() ? "<i>Hole populäre Posts...</i>" : "Suche: <code>" + escape(query) + "</code>"));
    }

    // Handler für Boorus (BooruSource)
    private void handleBooruShortcut(long chatId, String source, String[] args) {
        String query = String.join(" ", args).trim();
        QueueTask task = new QueueTask("BOORU_BATCH");
        // Wenn leer -> "all" für Random Logic (in BooruSource implementiert)
        task.addParameter("query", query.isEmpty() ? "all" : query);
        task.addParameter("source", source);
        task.addParameter("chatId", chatId);
        task.addParameter("amount", 1); // Standard: 1 Bild
        kernel.getQueueManager().addTask(task);

        String type = query.isEmpty() ? "🎲 Random" : "🔍 Tags";
        send(chatId, type + " gestartet: <b>" + source.toUpperCase() + "</b>\n" +
                (query.isEmpty() ? "<i>Würfle zufälliges Bild...</i>" : "Tags: <code>" + escape(query) + "</code>"));
    }

    // --- STANDARD HANDLER (Status, Queue, Admin...) Unverändert ---

    private void handleStatus(long chatId, String cmd, String[] args) {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        StringBuilder sb = new StringBuilder("📊 <b>SYSTEM STATUS</b>\n");
        sb.append("⏱ <b>Uptime:</b> ").append(String.format("%02d:%02d:%02d", uptime/3600, (uptime%3600)/60, uptime%60)).append("\n");

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            double cpu = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad() * 100;
            sb.append(String.format("💻 <b>CPU:</b> %.1f%%\n", cpu));
        }

        long freeGB = new File(".").getFreeSpace() / 1024 / 1024 / 1024;
        sb.append("💿 <b>Disk:</b> ").append(freeGB).append(" GB frei\n");
        sb.append("⚙️ <b>Queue:</b> ").append(kernel.getQueueManager().getQueueSize())
                .append(kernel.getQueueManager().isPaused() ? " ⏸️ (Pausiert)" : " ▶️ (Läuft)");

        send(chatId, sb.toString());
    }

    private void handleQueue(long chatId, String cmd, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
            if (!isAdmin(chatId)) return;
            kernel.getQueueManager().clearQueue();
            send(chatId, "🗑️ <b>Queue wurde geleert.</b>");
            return;
        }

        List<String> items = kernel.getQueueManager().getQueueList();
        if (items.isEmpty()) {
            send(chatId, "📭 <b>Die Warteschlange ist leer.</b>");
        } else {
            StringBuilder sb = new StringBuilder("📋 <b>WARTESCHLANGE</b>\n");
            int i = 0;
            for (String s : items) {
                if (++i > 15) {
                    sb.append("<i>... und ").append(items.size() - 15).append(" weitere.</i>");
                    break;
                }
                sb.append("<b>").append(i).append(".</b> ").append(escape(s)).append("\n");
            }
            send(chatId, sb.toString());
        }
    }

    private void handleStop(long chatId, String cmd, String[] args) {
        if (!isAdmin(chatId)) return;
        send(chatId, "🛑 <b>System fährt herunter...</b>");
        new Thread(() -> {
            try { Thread.sleep(2000); } catch(Exception e){}
            kernel.getTelegramListener().stopService();
            System.exit(0);
        }).start();
    }

    private void handleAllow(long chatId, String cmd, String[] args) {
        if (!isAdmin(chatId)) return;
        if (args.length < 1) { send(chatId, "⚠️ Syntax: <code>/allow [ID]</code>"); return; }
        long targetId = kernel.getUserManager().resolveId(args[0]);
        if (targetId != -1) {
            kernel.getAuthManager().addGuest(targetId);
            UserManager.UserData u = kernel.getUserManager().getUser(targetId);
            send(chatId, "✅ User <b>" + escape(u.firstName) + "</b> (" + targetId + ") freigeschaltet.");
        } else {
            send(chatId, "❌ User unbekannt.");
        }
    }

    private void handleRevoke(long chatId, String cmd, String[] args) {
        if (!isAdmin(chatId)) return;
        if (args.length < 1) return;
        long targetId = kernel.getUserManager().resolveId(args[0]);
        if (targetId != -1) {
            kernel.getAuthManager().removeGuest(targetId);
            send(chatId, "🚫 Zugriff für <code>" + targetId + "</code> entzogen.");
        }
    }

    private void handleWhois(long chatId, String cmd, String[] args) {
        if (!isAdmin(chatId)) return;
        if (args.length < 1) { send(chatId, "⚠️ Syntax: <code>/whois [user]</code>"); return; }
        long id = kernel.getUserManager().resolveId(args[0]);
        if (id != -1) {
            UserManager.UserData u = kernel.getUserManager().getUser(id);
            String role = kernel.getAuthManager().isAdmin(id) ? "ADMIN" : (kernel.getAuthManager().isGuest(id) ? "GUEST" : "USER");
            send(chatId, String.format("🕵️ <b>Akte:</b>\nID: <code>%d</code>\nName: %s\nRole: <b>%s</b>", u.id, escape(u.firstName), role));
        } else {
            send(chatId, "❌ Nicht gefunden.");
        }
    }

    private void handleClean(long chatId, String cmd, String[] args) {
        if (!isAdmin(chatId)) return;
        int count = cleanEmptyFolders(new File("media_cache"));
        send(chatId, "🧹 <b>Cleanup:</b> " + count + " leere Ordner entfernt.");
    }

    private void handleLog(long chatId, String cmd, String[] args) {
        if (!isAdmin(chatId)) return;
        File log = new File("logs/latest.log");
        if (!log.exists()) log = new File("session.log");
        if (!log.exists()) { send(chatId, "ℹ️ Kein Log gefunden."); return; }
        try {
            List<String> lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 15);
            StringBuilder sb = new StringBuilder("📝 <b>Letzte Log-Einträge:</b>\n");
            for (int i=start; i<lines.size(); i++) {
                String line = lines.get(i);
                if (line.length() > 100) line = line.substring(0, 100) + "...";
                sb.append("<code>").append(escape(line)).append("</code>\n");
            }
            send(chatId, sb.toString());
        } catch (IOException e) { send(chatId, "❌ Lesefehler."); }
    }

    private void send(long chatId, String text) {
        if (kernel.getTelegramListener() != null) {
            kernel.getTelegramListener().sendText(chatId, text);
        }
    }

    private boolean isAdmin(long chatId) {
        return kernel.getAuthManager().isAdmin(chatId);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int cleanEmptyFolders(File f) {
        int c = 0;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File sub : files) c += cleanEmptyFolders(sub);
            if (f.list() != null && f.list().length == 0) {
                f.delete();
                c++;
            }
        }
        return c;
    }
}