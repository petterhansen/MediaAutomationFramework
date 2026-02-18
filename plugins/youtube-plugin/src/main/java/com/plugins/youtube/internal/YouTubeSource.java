package com.plugins.youtube.internal;

import com.framework.core.queue.TaskExecutor;
import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTube TaskExecutor implementation using yt-dlp
 */
public class YouTubeSource implements TaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(YouTubeSource.class);
    private final Kernel kernel;
    private final Gson gson = new Gson();
    private final String ytDlpPath;

    public YouTubeSource(Kernel kernel, String ytDlpPath) {
        this.kernel = kernel;
        this.ytDlpPath = ytDlpPath;
    }

    @Override
    public void execute(QueueTask task) {
        String type = task.getString("type"); // "video", "channel", "playlist"
        String query = task.getString("query"); // URL or channel ID
        int amount = task.getInt("amount", 1);

        task.setStatus(QueueTask.Status.RUNNING);
        logger.info("🎥 Starting YouTube download: {} ({})", query, type);

        try {
            // FILTER: Check filetype
            String filterType = task.getString("filetype");
            if (filterType != null && "img".equalsIgnoreCase(filterType)) {
                logger.warn("❌ YouTube source does not support 'img' filter (Video only). Skipping: {}", query);
                task.setStatus(QueueTask.Status.DONE);
                return;
            }

            if ("video".equalsIgnoreCase(type)) {
                downloadSingleVideo(query, task);
            } else if ("channel".equalsIgnoreCase(type)) {
                downloadChannel(query, amount, task);
            } else if ("playlist".equalsIgnoreCase(type)) {
                downloadPlaylist(query, amount, task);
            } else {
                logger.error("Unknown YouTube type: {}", type);
            }
        } catch (Exception e) {
            logger.error("YouTube download error", e);
        }

        logger.info("✅ YouTube task complete: {}", query);
    }

    private void downloadSingleVideo(String url, QueueTask task) throws Exception {
        // Get video info first
        JsonObject info = getVideoInfo(url);
        if (info == null) {
            logger.error("Failed to get video info for: {}", url);
            return;
        }

        String title = info.get("title").getAsString();
        String uploader = info.has("uploader") ? info.get("uploader").getAsString() : "Unknown";

        task.setTotalItems(1);

        PipelineItem item = new PipelineItem(url, sanitizeFilename(title) + ".mp4", task);
        item.getMetadata().put("source", "youtube");
        item.getMetadata().put("creator", uploader);
        item.getMetadata().put("title", title);
        item.getMetadata().put("yt_dlp", true); // Signal to use yt-dlp for download
        item.getMetadata().put("yt_dlp_path", ytDlpPath); // Path to yt-dlp executable

        // Add yt-dlp arguments
        item.getMetadata().put("yt_dlp_args", buildYtDlpArgs());

        kernel.getPipelineManager().submit(item);
        logger.info("Queued video: {}", title);
    }

    private void downloadChannel(String channelUrl, int maxVideos, QueueTask task) throws Exception {
        // Get channel videos using yt-dlp
        List<JsonObject> videos = getChannelVideos(channelUrl, maxVideos);

        task.setTotalItems(videos.size());

        for (JsonObject video : videos) {
            String videoUrl = "https://www.youtube.com/watch?v=" + video.get("id").getAsString();
            String title = video.get("title").getAsString();
            String uploader = video.has("uploader") ? video.get("uploader").getAsString() : "Unknown";

            PipelineItem item = new PipelineItem(videoUrl, sanitizeFilename(title) + ".mp4", task);
            item.getMetadata().put("source", "youtube");
            item.getMetadata().put("creator", uploader);
            item.getMetadata().put("title", title);
            item.getMetadata().put("yt_dlp", true);
            item.getMetadata().put("yt_dlp_path", ytDlpPath);
            item.getMetadata().put("yt_dlp_args", buildYtDlpArgs());

            kernel.getPipelineManager().submit(item);
            logger.info("Queued: {}", title);
        }
    }

    private void downloadPlaylist(String playlistUrl, int maxVideos, QueueTask task) throws Exception {
        // Similar to channel, but for playlists
        List<JsonObject> videos = getPlaylistVideos(playlistUrl, maxVideos);

        task.setTotalItems(videos.size());

        for (JsonObject video : videos) {
            String videoUrl = "https://www.youtube.com/watch?v=" + video.get("id").getAsString();
            String title = video.get("title").getAsString();

            PipelineItem item = new PipelineItem(videoUrl, sanitizeFilename(title) + ".mp4", task);
            item.getMetadata().put("source", "youtube");
            item.getMetadata().put("creator", "playlist");
            item.getMetadata().put("title", title);
            item.getMetadata().put("yt_dlp", true);
            item.getMetadata().put("yt_dlp_path", ytDlpPath);
            item.getMetadata().put("yt_dlp_args", buildYtDlpArgs());

            kernel.getPipelineManager().submit(item);
        }
    }

    private JsonObject getVideoInfo(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ytDlpPath,
                "--dump-json",
                "--no-playlist",
                url);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            return null;
        }

        return gson.fromJson(output.toString(), JsonObject.class);
    }

    private List<JsonObject> getChannelVideos(String channelUrl, int maxVideos) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ytDlpPath,
                "--dump-json",
                "--flat-playlist",
                "--playlist-end", String.valueOf(maxVideos),
                channelUrl + "/videos");

        return executeJsonList(pb);
    }

    private List<JsonObject> getPlaylistVideos(String playlistUrl, int maxVideos) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ytDlpPath,
                "--dump-json",
                "--flat-playlist",
                "--playlist-end", String.valueOf(maxVideos),
                playlistUrl);

        return executeJsonList(pb);
    }

    private List<JsonObject> executeJsonList(ProcessBuilder pb) throws Exception {
        Process process = pb.start();
        List<JsonObject> results = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    results.add(gson.fromJson(line, JsonObject.class));
                }
            }
        }

        process.waitFor();
        return results;
    }

    private List<String> buildYtDlpArgs() {
        var config = kernel.getConfigManager().getConfig();
        List<String> args = new ArrayList<>();

        // Format (defaults to yt-dlp auto-selection if null/empty)
        String format = config.getPluginSetting("YouTube", "format", null);
        if (format != null && !format.isEmpty() && !format.equals("best")) {
            args.add("--format");
            args.add(format);
        }

        // Max filesize
        String maxSize = config.getPluginSetting("YouTube", "max_filesize", "500M");
        args.add("--max-filesize");
        args.add(maxSize);

        // Rate limit
        String rateLimit = config.getPluginSetting("YouTube", "ratelimit", "5M");
        args.add("--limit-rate");
        args.add(rateLimit);

        // Subtitles
        if (Boolean.parseBoolean(config.getPluginSetting("YouTube", "download_subtitles", "false"))) {
            args.add("--write-sub");
            args.add("--sub-lang");
            args.add("en");
        }

        // Thumbnail
        if (Boolean.parseBoolean(config.getPluginSetting("YouTube", "embed_thumbnail", "false"))) {
            args.add("--embed-thumbnail");
        }

        // Always use robust flags
        args.add("--no-part");
        args.add("--no-continue"); // Do not resume partially downloaded files, restart from scratch
        args.add("--no-mtime"); // Keep file modification time current
        args.add("--geo-bypass");
        args.add("--rm-cache-dir");
        args.add("--js-runtimes");
        args.add("node");

        // Point to local ffmpeg for merging video+audio streams
        args.add("--ffmpeg-location");
        args.add("tools/ffmpeg.exe");

        // Use a better User-Agent to avoid initial bot detection
        String ua = config.getPluginSetting("YouTube", "user_agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        args.add("--user-agent");
        args.add(ua);

        // Optional: Cookies from browser (highly recommended for Shorts/Age-gated)
        String browser = config.getPluginSetting("YouTube", "cookie_browser", "");
        if (browser != null && !browser.isEmpty()) {
            args.add("--cookies-from-browser");
            args.add(browser); // e.g., "chrome", "firefox"
        }

        return args;
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
