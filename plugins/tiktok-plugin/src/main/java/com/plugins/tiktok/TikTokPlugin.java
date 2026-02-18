package com.plugins.tiktok;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.pipeline.StageHandler;
import com.plugins.tiktok.internal.TikTokSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * TikTok video downloader plugin using yt-dlp
 */
public class TikTokPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(TikTokPlugin.class);
    private Kernel kernel;
    private TikTokSource source;
    private String ytDlpPath = null;

    @Override
    public String getName() {
        return "TikTok";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;

        // Check if yt-dlp is installed
        if (!checkYtDlpInstalled()) {
            logger.error("❌ yt-dlp not found! TikTok plugin requires yt-dlp.");
            return;
        }

        setupDefaults();

        this.source = new TikTokSource(kernel, ytDlpPath);
        kernel.getQueueManager().registerExecutor("tiktok", source);

        // Register /tt command
        kernel.registerCommand("/tt", this::handleTikTok);

        logger.info("✅ TikTok plugin enabled (using yt-dlp)");
        logger.info("   Usage: /tt <URL> or /tt user <username> <count>");
    }

    private void handleTikTok(long chatId, Integer threadId, String command, String[] args) {
        if (args.length < 1) {
            sendMessage(chatId, threadId,
                    "❌ Usage: /tt <URL> or /tt user <username> <count>");
            return;
        }

        String arg1 = args[0];
        String query;
        String type;
        int amount = 1;

        if (arg1.equalsIgnoreCase("user")) {
            if (args.length < 2) {
                sendMessage(chatId, threadId, "❌ Usage: /tt user <username> <count>");
                return;
            }
            type = "user";
            query = args[1];
            if (args.length > 2) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    amount = 1;
                }
            }
        } else {
            type = "video";
            query = arg1;
        }

        // Create and submit task
        var task = new com.framework.core.queue.QueueTask("tiktok");
        task.addParameter("type", type);
        task.addParameter("query", query);
        task.addParameter("amount", amount);
        task.addParameter("initiatorChatId", String.valueOf(chatId));
        if (threadId != null) {
            task.addParameter("initiatorThreadId", String.valueOf(threadId));
        }

        kernel.getQueueManager().addTask(task);

        String msg = String.format("🎵 TikTok %s queued: %s", type, query);
        if (amount > 1) {
            msg += " (" + amount + " items)";
        }
        sendMessage(chatId, threadId, msg);
    }

    private void sendMessage(long chatId, Integer threadId, String message) {
        kernel.sendMessage(chatId, threadId, message);
    }

    private boolean checkYtDlpInstalled() {
        // Check 1: Local tools directory
        File localYtDlp = new File("tools/yt-dlp.exe");
        if (localYtDlp.exists()) {
            ytDlpPath = localYtDlp.getAbsolutePath();
            return true;
        }

        // Check 2: System PATH
        try {
            Process process = new ProcessBuilder("yt-dlp", "--version").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                ytDlpPath = "yt-dlp"; // Use system PATH version
                return true;
            }
        } catch (Exception e) {
            // Not in PATH
        }

        return false;
    }

    private void setupDefaults() {
        var config = kernel.getConfigManager().getConfig();
        boolean dirty = false;

        // Set defaults if not configured
         if (config.getPluginSetting(getName(), "cookie_browser", "").isEmpty()) {
             config.setPluginSetting(getName(), "cookie_browser", ""); // Default empty
             dirty = true;
         }

         if (dirty) {
             kernel.getConfigManager().saveConfig();
         }
    }

    @Override
    public void onDisable() {
        logger.info("TikTok plugin disabled");
    }
}
