package com.plugins.youtube;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.pipeline.StageHandler;
import com.plugins.youtube.internal.YouTubeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * YouTube video downloader plugin using yt-dlp
 * Features:
 * - Download videos, channels, playlists
 * - No authentication required (public content only)
 * - Configurable quality and format
 * - Metadata extraction
 */
public class YouTubePlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(YouTubePlugin.class);
    private Kernel kernel;
    private YouTubeSource source;
    private String ytDlpPath = null; // Store the path to yt-dlp

    @Override
    public String getName() {
        return "YouTube";
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
            logger.error("❌ yt-dlp not found! Please install: pip install yt-dlp");
            logger.error("   Or download from: https://github.com/yt-dlp/yt-dlp/releases");
            return;
        }

        setupDefaults();

        this.source = new YouTubeSource(kernel, ytDlpPath);
        kernel.getQueueManager().registerExecutor("youtube", source);

        // Register Pipeline Download Handler
        // This makes YouTubePlugin self-contained and allows it to handle its own
        // downloads
        kernel.getPipelineManager().registerDownloadHandler(new StageHandler<>() {
            @Override
            public boolean supports(com.framework.core.pipeline.PipelineItem item) {
                // We handle items flagged with yt_dlp=true
                return Boolean.TRUE.equals(item.getMetadata().get("yt_dlp"));
            }

            @Override
            public File process(com.framework.core.pipeline.PipelineItem item) throws Exception {
                // Delegate to valid logic (reusing static helper or moving here)
                // For now, we move the download logic here.
                return downloadWithYtDlp(item);
            }
        });

        // Register /yt command
        kernel.registerCommand("/yt", this::handleYouTube);

        logger.info("✅ YouTube plugin enabled (yt-dlp found)");
        logger.info("   Usage: /yt video <URL> or /yt channel <URL> <count>");
    }

    private File downloadWithYtDlp(com.framework.core.pipeline.PipelineItem item) throws Exception {
        java.util.Map<String, Object> meta = item.getMetadata();
        String folder = (String) meta.get("creator");
        if (folder == null)
            folder = "downloads";

        // Use media_cache directory (next to tools, not inside)
        File cacheDir = new File("media_cache");
        File outputDir = new File(cacheDir, folder);
        outputDir.mkdirs();
        File outputFile = new File(outputDir, item.getOriginalName());

        String ytDlpPath = (String) meta.get("yt_dlp_path");
        if (ytDlpPath == null)
            ytDlpPath = "yt-dlp";

        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(ytDlpPath);

        @SuppressWarnings("unchecked")
        java.util.List<String> customArgs = (java.util.List<String>) meta.get("yt_dlp_args");
        if (customArgs != null)
            command.addAll(customArgs);

        command.add("-o");
        command.add(outputFile.getAbsolutePath());
        command.add(item.getSourceUrl());

        logger.info("🎥 Downloading with yt-dlp: {}", item.getSourceUrl());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("[download]") && line.contains("%"))
                    logger.info("  {}", line.trim());
                else
                    logger.info("yt-dlp: {}", line.trim());
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("yt-dlp failed with exit code: " + exitCode);

        // yt-dlp may create files with different extensions (.mp4, .webm, .mkv)
        // depending on merge operations
        // Check for the actual file created
        File actualFile = findActualOutputFile(outputFile);
        if (actualFile == null || !actualFile.exists())
            throw new RuntimeException("Output file not found: " + outputFile.getAbsolutePath() + " (or variants)");

        logger.info("✅ yt-dlp download complete: {}", actualFile.getName());
        return actualFile;
    }

    private File findActualOutputFile(File expectedFile) {
        // First check if the expected file exists
        if (expectedFile.exists()) {
            return expectedFile;
        }

        // Check for common video extensions that yt-dlp might use
        String[] extensions = { ".webm", ".mkv", ".mp4", ".m4a", ".flv", ".avi" };
        String basePath = expectedFile.getAbsolutePath();

        // First, try double extensions (e.g., file.mp4 -> file.mp4.webm)
        for (String ext : extensions) {
            File candidate = new File(basePath + ext);
            if (candidate.exists()) {
                return candidate;
            }
        }

        // Then try replacing the extension (e.g., file.mp4 -> file.webm)
        if (basePath.endsWith(".mp4")) {
            String baseWithoutExt = basePath.substring(0, basePath.length() - 4);
            for (String ext : extensions) {
                File candidate = new File(baseWithoutExt + ext);
                if (candidate.exists()) {
                    return candidate;
                }
            }
        }

        return null;
    }

    @Override
    public void onDisable() {
        logger.info("YouTube plugin disabled");
    }

    private boolean checkYtDlpInstalled() {
        // Check 1: Local tools directory
        File localYtDlp = new File("tools/yt-dlp.exe");
        if (localYtDlp.exists()) {
            ytDlpPath = localYtDlp.getAbsolutePath();
            logger.info("Found yt-dlp at: {}", ytDlpPath);
            return true;
        }

        // Check 2: Linux/Mac tools directory
        localYtDlp = new File("tools/yt-dlp");
        if (localYtDlp.exists()) {
            ytDlpPath = localYtDlp.getAbsolutePath();
            logger.info("Found yt-dlp at: {}", ytDlpPath);
            return true;
        }

        // Check 3: System PATH
        try {
            Process process = new ProcessBuilder("yt-dlp", "--version").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                ytDlpPath = "yt-dlp"; // Use system PATH version
                logger.info("Found yt-dlp in system PATH");
                return true;
            }
        } catch (Exception e) {
            // Not in PATH
        }

        return false;
    }

    private void setupDefaults() {
        var config = kernel.getConfigManager().getConfig();

        // Set defaults if not configured
        if (config.getPluginSetting(getName(), "format", "").isEmpty()) {
            // config.setPluginSetting(getName(), "format", "best"); // Removed to allow
            // yt-dlp default
            config.setPluginSetting(getName(), "max_filesize", "500M");
            config.setPluginSetting(getName(), "download_subtitles", "false");
            config.setPluginSetting(getName(), "embed_thumbnail", "false");
            config.setPluginSetting(getName(), "ratelimit", "5M"); // 5 MB/s max
            kernel.getConfigManager().saveConfig();
        }
    }

    /**
     * Handle /yt command: /yt video <URL> or /yt channel <URL> <count>
     */
    private void handleYouTube(long chatId, Integer threadId, String command, String[] args) {
        if (args.length < 2) {
            sendMessage(chatId, threadId,
                    "❌ Usage: /yt video <URL> or /yt channel <URL> <count> or /yt playlist <URL> <count>");
            return;
        }

        String type = args[0].toLowerCase(); // video, channel, or playlist
        String url = args[1];
        int amount = 1;

        if (args.length > 2) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                amount = 1;
            }
        }

        // Create and submit task
        var task = new com.framework.core.queue.QueueTask("youtube");
        task.addParameter("type", type);
        task.addParameter("query", url);
        task.addParameter("amount", amount);
        task.addParameter("initiatorChatId", String.valueOf(chatId));
        if (threadId != null) {
            task.addParameter("initiatorThreadId", String.valueOf(threadId));
        }

        kernel.getQueueManager().addTask(task);

        String msg = String.format("🎥 YouTube %s queued: %s", type, url);
        if (amount > 1) {
            msg += " (" + amount + " items)";
        }
        sendMessage(chatId, threadId, msg);
    }

    private void sendMessage(long chatId, Integer threadId, String message) {
        kernel.sendMessage(chatId, threadId, message);
    }
}
