package com.plugins.tiktok;

import com.framework.api.MediaPlugin;
import com.framework.api.DownloadSourceProvider;
import com.framework.common.DownloadRequest;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import com.framework.core.pipeline.StageHandler;
import com.plugins.tiktok.internal.TikTokSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * TikTok video downloader plugin using yt-dlp
 */
public class TikTokPlugin implements MediaPlugin, DownloadSourceProvider {
    private static final Logger logger = LoggerFactory.getLogger(TikTokPlugin.class);
    private Kernel kernel;
    private TikTokSource source;
    private String ytDlpPath = null;

    @Override
    public String getName() {
        return "tiktok";
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

        // Register as a DownloadSourceProvider
        kernel.getSourceDetectionService().registerProvider(this);

        // Register /tt command
        kernel.registerCommand("/tt", this::handleTikTok);

        // --- SLIDESHOW HANDLER ---
        // 1. Download Handler: Downloads images + audio, creates video
        kernel.getPipelineManager().registerDownloadHandler(new StageHandler<>() {
            @Override
            public boolean supports(com.framework.core.pipeline.PipelineItem item) {
                return Boolean.TRUE.equals(item.getMetadata().get("tiktok_slideshow"));
            }

            @Override
            public File process(com.framework.core.pipeline.PipelineItem item) throws Exception {
                return downloadSlideshow(item);
            }
        });

        // 2. Processing Handler: Returns [Video, Img1, Img2...]
        kernel.getPipelineManager().registerProcessingHandler(new StageHandler<>() {
            @Override
            public boolean supports(com.framework.core.pipeline.PipelineItem item) {
                return Boolean.TRUE.equals(item.getMetadata().get("tiktok_slideshow"));
            }

            @Override
            public java.util.List<File> process(com.framework.core.pipeline.PipelineItem item) throws Exception {
                File video = item.getDownloadedFile();
                java.util.List<File> allFiles = new java.util.ArrayList<>();
                if (video != null && video.exists()) {
                    allFiles.add(video);
                }

                // Append source images
                @SuppressWarnings("unchecked")
                java.util.List<String> imgPaths = (java.util.List<String>) item.getMetadata().get("tiktok_image_files");
                if (imgPaths != null) {
                    for (String path : imgPaths) {
                        File f = new File(path);
                        if (f.exists()) {
                            allFiles.add(f);
                        }
                    }
                }
                return allFiles;
            }
        });

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

    private File downloadSlideshow(com.framework.core.pipeline.PipelineItem item) throws Exception {
        @SuppressWarnings("unchecked")
        java.util.List<String> imageUrls = (java.util.List<String>) item.getMetadata().get("tiktok_images");
        String audioUrl = (String) item.getMetadata().get("tiktok_audio");
        String folderName = (String) item.getMetadata().get("creator");
        String baseName = item.getOriginalName().replace(".mp4", ""); // strip extension

        if (imageUrls == null || imageUrls.isEmpty()) {
            throw new Exception("No images found for slideshow.");
        }

        File cacheDir = new File("media_cache");
        File creatorDir = new File(cacheDir, folderName);
        File slideDir = new File(creatorDir, baseName + "_slides"); // Subfolder for this post
        if (!slideDir.mkdirs() && !slideDir.exists())
            throw new java.io.IOException("Could not create dir: " + slideDir);

        logger.info("📸 Downloading {} images for slideshow...", imageUrls.size());

        java.util.List<File> downloadedImages = new java.util.ArrayList<>();
        int idx = 0;
        for (String url : imageUrls) {
            String ext = ".jpg";
            if (url.contains(".webp"))
                ext = ".webp";
            File target = new File(slideDir, String.format("img_%03d%s", idx++, ext));
            downloadFile(url, target);
            downloadedImages.add(target);
        }

        // Save image paths to metadata for the Processing step
        java.util.List<String> savedPaths = new java.util.ArrayList<>();
        for (File f : downloadedImages)
            savedPaths.add(f.getAbsolutePath());
        item.getMetadata().put("tiktok_image_files", savedPaths);

        File audioFile = null;
        if (audioUrl != null) {
            audioFile = new File(slideDir, "audio.mp3");
            downloadFile(audioUrl, audioFile);
        }

        // Generate Video using ffmpeg
        File outputVideo = new File(creatorDir, item.getOriginalName());

        String ffmpegPath = "tools/ffmpeg.exe"; // Fallback
        if (!new File(ffmpegPath).exists())
            ffmpegPath = "ffmpeg"; // PATH

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");

        cmd.add("-framerate");
        cmd.add("0.5");

        File firstImg = downloadedImages.get(0);
        String ext = firstImg.getName().substring(firstImg.getName().lastIndexOf("."));
        cmd.add("-i");
        cmd.add(new File(slideDir, "img_%03d" + ext).getAbsolutePath());

        if (audioFile != null) {
            cmd.add("-i");
            cmd.add(audioFile.getAbsolutePath());
        }

        cmd.add("-vf");
        cmd.add("scale=1080:-2,format=yuv420p");

        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-r");
        cmd.add("30"); // Output fps
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");

        if (audioFile != null) {
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-shortest");
        }

        cmd.add(outputVideo.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
            }
        }
        p.waitFor();

        if (outputVideo.exists()) {
            logger.info("✅ Generated Slideshow Video: {}", outputVideo.getName());
            return outputVideo;
        } else {
            throw new Exception("FFmpeg failed to generate slideshow.");
        }
    }

    private void downloadFile(String url, File target) throws Exception {
        java.net.URL u = java.net.URI.create(url).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (java.io.InputStream in = conn.getInputStream();
                java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        }
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

        // Ensure TikTok settings map exists
        var settings = config.pluginConfigs.computeIfAbsent(getName(), k -> new java.util.HashMap<>());

        // Ensure keys exist so they appear in config.json
        if (!settings.containsKey("cookie_browser")) {
            settings.put("cookie_browser", "");
            dirty = true;
        }

        if (!settings.containsKey("cookie_file")) {
            settings.put("cookie_file", "");
            dirty = true;
        }

        if (dirty) {
            kernel.getConfigManager().saveConfig();
        }
    }

    @Override
    public void onDisable() {
        if (kernel != null) {
            kernel.getSourceDetectionService().unregisterProvider(this);
        }
        logger.info("TikTok plugin disabled");
    }

    // --- DownloadSourceProvider Implementation ---

    @Override
    public boolean canHandle(String query) {
        return query != null && query.toLowerCase().contains("tiktok.com");
    }

    @Override
    public QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
        QueueTask task = new QueueTask("tiktok");
        
        // If query is a user profile URL or explicit source is "tiktok", we assume default handling
        // TikTok /tt command allows explicit "user" flag. For /dl, we just pass the URL as video unless overridden.
        task.addParameter("type", "video");
        task.addParameter("query", req.query());
        int amount = req.amount() > 0 ? req.amount() : 1;
        task.addParameter("amount", amount);
        
        if (req.filetype() != null) {
            task.addParameter("filetype", req.filetype());
        }

        if (chatId != 0) {
            task.addParameter("initiatorChatId", String.valueOf(chatId));
            if (threadId != null) {
                task.addParameter("initiatorThreadId", String.valueOf(threadId));
            }
        }

        return task;
    }
}
