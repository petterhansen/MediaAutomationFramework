package com.plugins.coremedia;

import com.framework.api.CommandHandler;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class SystemCommandHandler implements CommandHandler {
    private static final Logger logger = LoggerFactory.getLogger(SystemCommandHandler.class);
    private final Kernel kernel;

    public SystemCommandHandler(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void handle(long chatId, Integer threadId, String command, String[] args) {
        try {
            switch (command.toLowerCase()) {
                case "/stop":
                    handleStop(chatId, threadId);
                    break;
                case "/pause":
                    handlePause(chatId, threadId);
                    break;
                case "/resume":
                    handleResume(chatId, threadId);
                    break;
                case "/queue":
                    if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
                        handleQueueClear(chatId, threadId);
                    } else {
                        // Regular queue display is assumed to be handled elsewhere, or falling through
                    }
                    break;
                case "/log":
                    handleLog(chatId, threadId, args);
                    break;
                case "/disk":
                case "/storage":
                case "/sys":
                    handleDisk(chatId, threadId);
                    break;
            }
        } catch (Exception e) {
            logger.error("System Command Error", e);
            kernel.sendMessage(chatId, threadId, "❌ <b>System Error:</b> " + e.getMessage());
        }
    }

    private void handleStop(long chatId, Integer threadId) {
        // Emergency stop: Pause queue and maybe stop downloads?
        // MAF doesn't have a full "Emergency Stop" that kills threads safely yet,
        // so we just pause the queue and notify.
        kernel.getQueueManager().setPaused(true);
        // kernel.getPipelineManager().stop(); // Too aggressive?
        kernel.sendMessage(chatId, threadId,
                "🛑 <b>EMERGENCY STOP!</b> Queue paused. Active downloads might continue.");
    }

    private void handlePause(long chatId, Integer threadId) {
        kernel.getQueueManager().setPaused(true);
        kernel.sendMessage(chatId, threadId, "⏸️ Queue paused.");
    }

    private void handleResume(long chatId, Integer threadId) {
        kernel.getQueueManager().setPaused(false);
        kernel.sendMessage(chatId, threadId, "▶️ Queue resumed.");
    }

    private void handleQueueClear(long chatId, Integer threadId) {
        kernel.getQueueManager().clearQueue();
        kernel.getPipelineManager().clear(); // Also clear pipeline queues?
        kernel.sendMessage(chatId, threadId, "🗑️ Queue and Pipeline cleared.");
    }

    private void handleLog(long chatId, Integer threadId, String[] args) {
        int linesToRead = 10;
        if (args.length > 0) {
            try {
                linesToRead = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        // Correct log path for MAF? Assuming logs/latest.log or similar exists
        File logFile = new File("logs/latest.log");
        if (!logFile.exists()) {
            // Try fallback
            logFile = new File("session.log");
        }

        if (!logFile.exists()) {
            kernel.sendMessage(chatId, threadId, "ℹ️ No Log-File found.");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - linesToRead);
            StringBuilder sb = new StringBuilder("📝 <b>Last " + linesToRead + " Log-Lines:</b>\n\n");
            for (int i = start; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.length() > 100)
                    l = l.substring(0, 100) + "...";
                sb.append("<code>").append(escape(l)).append("</code>\n");
            }
            kernel.sendMessage(chatId, threadId, sb.toString());
        } catch (IOException e) {
            kernel.sendMessage(chatId, threadId, "❌ Error reading log: " + e.getMessage());
        }
    }

    private void handleDisk(long chatId, Integer threadId) {
        File root = new File(".");
        long free = root.getFreeSpace() / 1024 / 1024 / 1024;
        long total = root.getTotalSpace() / 1024 / 1024 / 1024;

        // Memory stats
        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory() / 1024 / 1024;
        long usedMem = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;

        StringBuilder sb = new StringBuilder("💿 <b>System Resources:</b>\n\n");
        sb.append("💾 <b>Disk:</b> ").append(free).append(" GB free / ").append(total).append(" GB total\n");
        sb.append("🧠 <b>RAM:</b> ").append(usedMem).append(" MB used / ").append(maxMem).append(" MB max\n");

        kernel.sendMessage(chatId, threadId, sb.toString());
    }

    private String escape(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
