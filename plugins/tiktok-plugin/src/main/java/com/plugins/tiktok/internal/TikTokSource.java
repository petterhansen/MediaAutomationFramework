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

        String title = info.has("title") ? info.get("title").getAsString() : "TikTok";
        String uploader = info.has("uploader") ? info.get("uploader").getAsString() : "Unknown";
        String id = info.get("id").getAsString();

        task.setTotalItems(1);

        // Check for "entries" (Playlist) - potential slideshow
        if (info.has("entries") && info.get("entries").isJsonArray()) {
            var entries = info.get("entries").getAsJsonArray();
            if (entries.size() > 0) {
                List<String> entryImages = new ArrayList<>();
                for (var e : entries) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("url")) {
                        if (url.contains("/video/") || url.contains("/photo/")) {
                            entryImages.add(e.getAsJsonObject().get("url").getAsString());
                        }
                    }
                }
                if (!entryImages.isEmpty()) {
                    logger.info("📸 Detected TikTok Slideshow (via entries): {} items", entryImages.size());

                    PipelineItem item = new PipelineItem(url, sanitizeFilename(uploader + "_" + id) + ".mp4", task);
                    item.getMetadata().put("source", "tiktok");
                    item.getMetadata().put("creator", uploader);
                    item.getMetadata().put("title", title);
                    item.getMetadata().put("tiktok_slideshow", true);
                    item.getMetadata().put("yt_dlp", false);
                    item.getMetadata().put("tiktok_images", entryImages);

                    if (info.has("url")) {
                        item.getMetadata().put("tiktok_audio", info.get("url").getAsString());
                    }

                    kernel.getPipelineManager().submit(item);
                    logger.info("Queued TikTok Slideshow: {} - {}", uploader, title);
                    return;
                }
            }
        }

        // Check for Slideshow (Images)
        if (info.has("images") && info.get("images").isJsonArray() && info.get("images").getAsJsonArray().size() > 0) {
            logger.info("📸 Detected TikTok Slideshow: {} images", info.get("images").getAsJsonArray().size());

            PipelineItem item = new PipelineItem(url, sanitizeFilename(uploader + "_" + id) + ".mp4", task);
            item.getMetadata().put("source", "tiktok");
            item.getMetadata().put("creator", uploader);
            item.getMetadata().put("title", title);
            item.getMetadata().put("tiktok_slideshow", true);
            item.getMetadata().put("yt_dlp", false); // We handle this ourselves

            // Extract Images
            List<String> imageUrls = new ArrayList<>();
            for (var img : info.get("images").getAsJsonArray()) {
                // yt-dlp images usually have 'url'
                if (img.isJsonObject() && img.getAsJsonObject().has("url")) {
                    imageUrls.add(img.getAsJsonObject().get("url").getAsString());
                }
            }
            item.getMetadata().put("tiktok_images", imageUrls);

            // Extract Audio (if available) - typically 'url' in 'requested_downloads' or
            // distinct field
            // But yt-dlp dump-json for slideshows often has 'url' at top level for the
            // audio track or separate entry
            // We'll try to find a valid audio url. For TikTok, often 'url' points to the
            // music.
            if (info.has("url")) {
                item.getMetadata().put("tiktok_audio", info.get("url").getAsString());
            } else if (info.has("requested_downloads")) {
                // Sometimes audio is here
                // Simplified: Just check if we can find a format?
                // For now rely on 'url' or fallback to silent slideshow
            }

            kernel.getPipelineManager().submit(item);
            logger.info("Queued TikTok Slideshow: {} - {}", uploader, title);
            return;
        }

        // Warning for Audio-Only (likely missing cookies)
        if (info.has("resolution") && "audio only".equals(info.get("resolution").getAsString())) {
            logger.warn("⚠️ TikTok returned 'audio only' and no images found. This is likely a slideshow.");
            logger.warn("👉 Try setting a 'cookie_browser' in configuration to fix this.");
        }

        // Normal Video
        PipelineItem item = new PipelineItem(url, sanitizeFilename(uploader + "_" + id) + ".mp4", task);
        item.getMetadata().put("source", "tiktok");
        item.getMetadata().put("creator", uploader);
        item.getMetadata().put("title", title);
        item.getMetadata().put("yt_dlp", true); // Handle by YouTubePlugin
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

        // Cookies from file
        String cookieFile = config.getPluginSetting("TikTok", "cookie_file", "");
        if (cookieFile != null && !cookieFile.isEmpty()) {
            args.add("--cookies");
            args.add(cookieFile);
        }

        return args;
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
