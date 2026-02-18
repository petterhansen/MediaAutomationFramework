package com.plugins.socials.internal;

import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import com.framework.core.queue.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SocialsSource implements TaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(SocialsSource.class);
    private final Kernel kernel;
    private final String ytDlpPath;

    public SocialsSource(Kernel kernel, String ytDlpPath) {
        this.kernel = kernel;
        this.ytDlpPath = ytDlpPath;
    }

    @Override
    public void execute(QueueTask task) {
        String query = task.getString("query");
        if (query == null) return;

        // Use a temporary unique name, real name comes from yt-dlp later
        String uniqueId = java.util.UUID.randomUUID().toString();
        String filenameTemplate = "social_" + uniqueId + ".mp4"; // Default extension, corrected later

        PipelineItem item = new PipelineItem(query, filenameTemplate, task);
        
        // Flag for our download handler in SocialsPlugin
        item.getMetadata().put("socials_dl", true);
        item.getMetadata().put("yt_dlp_path", ytDlpPath);
        
        // Determine "creator" / source folder based on URL
        String folder = determineFolder(query);
        item.getMetadata().put("creator", folder);
        item.getMetadata().put("source", "socials");
        
        if (task.getParameter("amount") != null) {
            item.getMetadata().put("amount", task.getParameter("amount"));
        }

        // Submit to pipeline - SocialsPlugin.downloadWithYtDlp will pick it up
        kernel.getPipelineManager().submit(item);
    }

    private String determineFolder(String url) {
        if (url.contains("instagram.com")) return "instagram";
        if (url.contains("facebook.com") || url.contains("fb.watch")) return "facebook";
        if (url.contains("threads.net")) return "threads";
        if (url.contains("twitter.com") || url.contains("x.com")) return "twitter";
        if (url.contains("reddit.com") || url.contains("redd.it")) return "reddit";
        if (url.contains("pinterest.com") || url.contains("pin.it")) return "pinterest";
        return "socials";
    }

    // This method is called by the Pipeline via the StageHandler registered in SocialsPlugin
    public File downloadWithYtDlp(PipelineItem item) throws Exception {
        Map<String, Object> meta = item.getMetadata();
        String folder = (String) meta.get("creator");
        
        File cacheDir = new File("media_cache");
        File outputDir = new File(cacheDir, folder);
        outputDir.mkdirs();
        
        // Define output template for yt-dlp
        // We use %(title)s [%(id)s].%(ext)s to get a nice filename
        String template = "%(title)s [%(id)s].%(ext)s";
        
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("-o");
        command.add(new File(outputDir, template).getAbsolutePath());
        
        // Add User-Agent to avoid some blocks (optional but good practice)
        command.add("--user-agent");
        command.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        
        // Check for cookies
        // Priority:
        // 1. cookies/{source}_cookies.txt (Best)
        // 2. {source}_cookies.txt (Root)
        // 3. tools/{source}_cookies.txt (Tools)
        // 4. cookies/cookies.txt (Generic in Dir)
        // 5. cookies.txt (Generic Root)
        // 6. tools/cookies.txt (Generic Tools)
        
        String sourceName = folder; // "twitter", "reddit", etc.
        File cookiesFile = null;
        
        // 1. Check cookies/{source}_cookies.txt
        File specificDir = new File("cookies/" + sourceName + "_cookies.txt");
        if (specificDir.exists()) cookiesFile = specificDir;
        else {
            // 2. Check {source}_cookies.txt
            File specificRoot = new File(sourceName + "_cookies.txt");
            if (specificRoot.exists()) cookiesFile = specificRoot;
            else {
                // 3. Check tools/{source}_cookies.txt
                File specificTools = new File("tools/" + sourceName + "_cookies.txt");
                if (specificTools.exists()) cookiesFile = specificTools;
            }
        }
        
        // Fallback to generic if specific not found
        if (cookiesFile == null) {
            // 4. Check cookies/cookies.txt
            File genericDir = new File("cookies/cookies.txt");
            if (genericDir.exists()) cookiesFile = genericDir;
            else {
                // 5. Check cookies.txt
                File genericRoot = new File("cookies.txt");
                if (genericRoot.exists()) cookiesFile = genericRoot;
                else {
                    // 6. Check tools/cookies.txt
                    File genericTools = new File("tools/cookies.txt");
                    if (genericTools.exists()) cookiesFile = genericTools;
                }
            }
        }
        
        if (cookiesFile != null) {
            logger.info("🍪 Using cookies from: {}", cookiesFile.getAbsolutePath());
            command.add("--cookies");
            command.add(cookiesFile.getAbsolutePath());
        }
        
        // Handle amount limit for playlists/feeds
        Object amountObj = item.getMetadata().get("amount");
        if (amountObj != null) {
            try {
                int amount = Integer.parseInt(amountObj.toString());
                if (amount > 1) {
                    logger.info("🔢 Limit set to: {}", amount);
                    command.add("--playlist-end");
                    command.add(String.valueOf(amount));
                } else {
                    // For single downloads or default 1, we might want to ensure we don't download a whole playlist if URL is a feed
                    // But usually 1 is default. If the URL is a single video, this flag is ignored or harmless.
                    // If URL is a profile and amount is 1, we only want the latest.
                    command.add("--playlist-end");
                    command.add("1");
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid amount format: {}", amountObj);
            }
        } else {
             // Default to 1 if not specified, to prevent accidental full profile downloads
             // unless we know it's a specific video.
             // But detecting if it's a profile or video is hard without probing.
             // Safest for "socials" source which traps generic URLs is to default to 1 if generic.
             // But existing behavior was "download default".
             // Let's safe-guard: if no amount, default to 1 for safety? 
             // Or let yt-dlp decide?
             // If user pastes a profile URL without /x command, they might expect all? Or just one?
             // Standard /dl usually checks 1.
             // Let's add --playlist-end 1 by default if amount is missing/null, to avoid accidents.
             // UNLESS it's explicitly a playlist download task?
             // Task usually comes from /dl which defaults amount=1 if not set.
             command.add("--playlist-end");
             command.add("1");
        }
        
        command.add(item.getSourceUrl());

        logger.info("📱 Downloading social media: {}", item.getSourceUrl());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> outputLines = new ArrayList<>();
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
                if (line.contains("[download]") && line.contains("%"))
                     {} // quiet progress
                else
                    logger.debug("yt-dlp: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("❌ yt-dlp failed for URL: {}", item.getSourceUrl());
            logger.error("Exit Code: {}", exitCode);
            logger.error("Output Dump:");
            for (String s : outputLines) {
                logger.error(s);
            }
            throw new RuntimeException("yt-dlp failed with exit code: " + exitCode);
        }

        // Find the actual file that was created
        // Since we don't know the exact filename (title is dynamic), we look for the newest file in the folder
        // This is a heuristic but works well for single downloads in a dedicated folder
        File downloadedFile = findNewestFile(outputDir);
        
        if (downloadedFile == null) {
             throw new RuntimeException("Download appeared successful but no file found in " + outputDir);
        }
        
        return downloadedFile;
    }
    
    private File findNewestFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return null;
        
        File newest = null;
        long lastMod = Long.MIN_VALUE;
        
        for (File f : files) {
            if (f.isFile() && f.lastModified() > lastMod) {
                lastMod = f.lastModified();
                newest = f;
            }
        }
        return newest;
    }
}
