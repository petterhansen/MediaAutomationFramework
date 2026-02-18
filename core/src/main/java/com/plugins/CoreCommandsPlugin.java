package com.plugins;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
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
    public String getName() {
        return "CoreCommands";
    }

    @Override
    public String getVersion() {
        return "1.5.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;

        // Service registrieren für Dashboard-Zugriff
        kernel.registerService(CoreCommandsPlugin.class, this);

        // Telegram Befehle registrieren
        kernel.registerCommand("/help", this::handleHelp);
        kernel.registerCommand("/start", this::handleHelp);
        kernel.registerCommand("/status", this::handleStatus);
        kernel.registerCommand("/stop", this::handleStop);

        kernel.registerCommand("/queue", this::handleQueue);
        kernel.registerCommand("/q", this::handleQueue);

        // Admin Tools
        kernel.registerCommand("/clean", this::handleClean);
        kernel.registerCommand("/log", this::handleLog);

        // Local Files / Upload
        kernel.registerCommand("/local", this::handleLocal);
        kernel.registerCommand("/upload", this::handleLocal);

        // shortcuts removed - moved to plugins

        // Info für Settings
        kernel.getConfigManager().getConfig().setPluginSetting(
                getName(),
                "commands_info",
                "/help, /status, /queue, /log, /clean, /local");
        kernel.getConfigManager().saveConfig();
    }

    @Override
    public void onDisable() {
    }

    // =================================================================================
    // PUBLIC API (String Rückgabe für Dashboard & Chat)
    // =================================================================================

    public String getStatusText() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double cpu = (osBean instanceof com.sun.management.OperatingSystemMXBean)
                ? ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad() * 100
                : 0;
        long freeGB = new File(".").getFreeSpace() / 1024 / 1024 / 1024;

        return String.format(
                "📊 <b>SYSTEM STATUS</b>\n⏱ <b>Uptime:</b> %02d:%02d:%02d\n💻 <b>CPU:</b> %.1f%%\n💿 <b>Disk:</b> %d GB frei\n⚙️ <b>Queue:</b> %d %s",
                uptime / 3600, (uptime % 3600) / 60, uptime % 60,
                cpu, freeGB,
                kernel.getQueueManager().getQueueSize(),
                kernel.getQueueManager().isPaused() ? "⏸️ (Pausiert)" : "▶️ (Läuft)");
    }

    public String getQueueText() {
        List<String> items = kernel.getQueueManager().getQueueList();
        if (items.isEmpty())
            return "📭 <b>Die Warteschlange ist leer.</b>";

        StringBuilder sb = new StringBuilder("📋 <b>WARTESCHLANGE</b>\n");
        int i = 0;
        for (String s : items) {
            if (++i > 15) {
                sb.append("<i>... und ").append(items.size() - 15).append(" weitere.</i>");
                break;
            }
            sb.append("<b>").append(i).append(".</b> ").append(escape(s)).append("\n");
        }
        return sb.toString();
    }

    public String getLogText() {
        File log = new File("logs/latest.log");
        if (!log.exists())
            return "ℹ️ Kein Log gefunden.";
        try {
            List<String> lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 20);
            StringBuilder sb = new StringBuilder("📝 <b>Letzte Log-Einträge:</b>\n");
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.length() > 120)
                    line = line.substring(0, 120) + "...";
                sb.append("<code>").append(escape(line)).append("</code>\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "❌ Lesefehler: " + e.getMessage();
        }
    }

    public String executeClean() {
        int count = cleanEmptyFolders(new File("media_cache"));
        return "🧹 <b>Cleanup:</b> " + count + " leere Ordner entfernt.";
    }

    public String getHelpText() {
        return "📚 <b>Web-Console Help</b>\n" +
                "Nutze /status, /queue, /log, /clean";
    }

    public String getUptime() {
        return "Running";
    }

    // =================================================================================
    // TELEGRAM HANDLERS (Void -> Send)
    // =================================================================================

    private void handleStatus(long id, Integer threadId, String c, String[] a) {
        send(id, threadId, getStatusText());
    }

    private void handleQueue(long id, Integer threadId, String c, String[] a) {
        send(id, threadId, getQueueText());
    }

    private void handleLog(long id, Integer threadId, String c, String[] a) {
        send(id, threadId, getLogText());
    }

    private void handleClean(long id, Integer threadId, String c, String[] a) {
        send(id, threadId, executeClean());
    }

    private void handleHelp(long id, Integer threadId, String c, String[] a) {
        send(id, threadId, getHelpText());
    } // Vereinfacht

    private void handleStop(long id, Integer threadId, String c, String[] a) {
        send(id, threadId, "🛑 System stoppt...");

        // Spawn a new thread to exit after a delay, allowing the listener to
        // acknowledge the update
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
            logger.info("Shutdown requested via Telegram. Exiting...");
            System.exit(0);
        }).start();
    }

    private void handleLocal(long id, Integer threadId, String c, String[] a) {
        if (a.length < 1) {
            send(id, threadId, "⚠️ Syntax: <code>/local [FolderName] [Amount optional]</code>");
            return;
        }

        String folder = a[0];
        int amount = -1;
        if (a.length > 1) {
            try {
                amount = Integer.parseInt(a[1]);
            } catch (Exception e) {
            }
        }

        // We use the new QueueTask constructor and manually set parameters matching
        // LocalTaskExecutor expectations
        com.framework.core.queue.QueueTask task = new com.framework.core.queue.QueueTask("LOCAL_BATCH");
        task.addParameter("folder", folder);
        task.addParameter("amount", amount);
        task.addParameter("initiatorChatId", String.valueOf(id));
        if (threadId != null)
            task.addParameter("initiatorThreadId", String.valueOf(threadId));

        // Use folder name as title for dashboard
        task.addParameter("name", "#" + folder);

        kernel.getQueueManager().addTask(task);
        send(id, threadId, "📂 <b>Local Upload started:</b> " + escape(folder));
    }

    // Shortcuts Logic removed - moved to plugins

    // Helpers
    private void send(long chatId, Integer threadId, String text) {
        kernel.sendMessage(chatId, threadId, text);
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int cleanEmptyFolders(File f) {
        int c = 0;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File sub : files)
                    c += cleanEmptyFolders(sub);
            }
            String[] list = f.list();
            if (list != null && list.length == 0) {
                f.delete();
                c++;
            }
        }
        return c;
    }
}