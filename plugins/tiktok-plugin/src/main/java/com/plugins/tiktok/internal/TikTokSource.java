package com.plugins.tiktok.internal;

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
 * TikTok TaskExecutor implementation using yt-dlp
 */
public class TikTokSource implements TaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(TikTokSource.class);
    private final Kernel kernel;
    private final Gson gson = new Gson();
    private final String ytDlpPath;

    public TikTokSource(Kernel kernel, String ytDlpPath) {
        this.kernel = kernel;
        this.ytDlpPath = ytDlpPath;
    }

    @Override
    public void execute(QueueTask task) {
        String type = task.getString("type"); // "video", "user"
        String query = task.getString("query"); // URL or username
        int amount = task.getInt("amount", 1);

        task.setStatus(QueueTask.Status.RUNNING);
        logger.info("🎵 Starting TikTok download: {} ({})", query, type);

        try {
            if ("video".equalsIgnoreCase(type)) {
                downloadSingleVideo(query, task);
            } else if ("user".equalsIgnoreCase(type)) {
                downloadUser(query, amount, task);
            } else {
                logger.error("Unknown TikTok type: {}", type);
            }
        } catch (Exception e) {
            logger.error("TikTok download error", e);
        }

        logger.info("✅ TikTok task complete: {}", query);
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
        String id = info.get("id").getAsString();
        
        task.setTotalItems(1);

        PipelineItem item = new PipelineItem(url, sanitizeFilename(uploader + "_" + id) + ".mp4", task);
        item.getMetadata().put("source", "tiktok");
        item.getMetadata().put("creator", uploader);
        item.getMetadata().put("title", title);
        item.getMetadata().put("yt_dlp", true); // Reuse YouTubePlugin's yt-dlp handler if available, OR we can handle it directly.
        // Since we didn't register a handler in TikTokPlugin, we must act as a source that generates items 
        // that are compatible with YouTubePlugin's handler OR implement our own handler.
        // HACK: Since YouTubePlugin registers a global handler for "yt_dlp=true", we can reuse it!
        item.getMetadata().put("yt_dlp_path", ytDlpPath); 
        item.getMetadata().put("yt_dlp_args", buildYtDlpArgs());

        kernel.getPipelineManager().submit(item);
        logger.info("Queued TikTok: {} - {}", uploader, title);
    }

    private void downloadUser(String username, int maxVideos, QueueTask task) throws Exception {
        // Construct user URL
        String userUrl = username.startsWith("http") ? username : "https://www.tiktok.com/@" + username;

        List<JsonObject> videos = getUserVideos(userUrl, maxVideos);
        task.setTotalItems(videos.size());

        for (JsonObject video : videos) {
            String videoUrl = video.get("webpage_url").getAsString(); // yt-dlp provides webpage_url
            String title = video.has("title") ? video.get("title").getAsString() : "TikTok Video";
            String uploader = video.has("uploader") ? video.get("uploader").getAsString() : username;
            String id = video.get("id").getAsString();

            PipelineItem item = new PipelineItem(videoUrl, sanitizeFilename(uploader + "_" + id) + ".mp4", task);
            item.getMetadata().put("source", "tiktok");
            item.getMetadata().put("creator", uploader);
            item.getMetadata().put("title", title);
            item.getMetadata().put("yt_dlp", true);
            item.getMetadata().put("yt_dlp_path", ytDlpPath);
            item.getMetadata().put("yt_dlp_args", buildYtDlpArgs());

            kernel.getPipelineManager().submit(item);
            logger.info("Queued: {}", title);
        }
    }

    private JsonObject getVideoInfo(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ytDlpPath,
                "--dump-json",
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

    private List<JsonObject> getUserVideos(String userUrl, int maxVideos) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ytDlpPath,
                "--dump-json",
                "--flat-playlist",
                "--playlist-end", String.valueOf(maxVideos),
                userUrl);

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

        // TikTok specific args might be needed
        
        // Cookies from browser are crucial for TikTok
        String browser = config.getPluginSetting("TikTok", "cookie_browser", "");
        if (browser != null && !browser.isEmpty()) {
            args.add("--cookies-from-browser");
            args.add(browser);
        }

        return args;
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
