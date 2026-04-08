package com.plugins.gallerydl.internal;

import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import com.framework.core.queue.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GalleryDlSource implements TaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(GalleryDlSource.class);
    private final Kernel kernel;
    private final String galleryDlPath;

    public GalleryDlSource(Kernel kernel, String galleryDlPath) {
        this.kernel = kernel;
        this.galleryDlPath = galleryDlPath;
    }

    @Override
    public void execute(QueueTask task) {
        String url = task.getString("query");
        if (url == null || url.isEmpty()) return;

        task.setStatus(QueueTask.Status.RUNNING);
        logger.info("📦 Starting gallery-dl download for: {}", url);

        try {
            // Use user-specified folder if available, otherwise fallback to random
            String folderParam = task.getString("folder");
            File cacheDir;
            if (folderParam != null && !folderParam.isEmpty()) {
                // Sanitize folder name to prevent path injection
                String sanitized = folderParam.replaceAll("[^a-zA-Z0-9_.-]", "_");
                cacheDir = new File("media_cache", sanitized);
            } else {
                cacheDir = new File("media_cache", "gallerydl_" + UUID.randomUUID().toString().substring(0, 8));
            }

            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw new RuntimeException("Failed to create cache directory: " + cacheDir.getAbsolutePath());
            }

            // Execute gallery-dl to download files into cacheDir
            List<String> cmd = new ArrayList<>();
            cmd.add(galleryDlPath);
            cmd.add("--directory");
            cmd.add(cacheDir.getAbsolutePath());
            cmd.add(url);

            logger.info("Executing command: {}", String.join(" ", cmd));
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("gallery-dl: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("⚠️ gallery-dl finished with non-zero exit code: {}", exitCode);
            }

            // Now list downloaded items RECURSIVELY and push to pipeline
            List<File> allFiles = new ArrayList<>();
            findFilesRecursive(cacheDir, allFiles);
            
            if (allFiles.isEmpty()) {
                logger.warn("❌ No files were found in cache after gallery-dl run for: {}", url);
                task.setStatus(QueueTask.Status.DONE);
                return;
            }

            String creator = task.getString("folder");
            if (creator == null || creator.isEmpty()) {
                // Fallback to domain name if no folder specified
                try {
                    creator = new java.net.URL(url).getHost().replace("www.", "");
                } catch (Exception e) {
                    creator = "gallery-dl";
                }
            }

            task.setTotalItems(allFiles.size());
            for (File file : allFiles) {
                PipelineItem item = new PipelineItem(url + "#" + file.getName(), file.getName(), task);
                item.setDownloadedFile(file); // Explicitly set the file
                
                item.getMetadata().put("gallery_dl_downloaded", true);
                item.getMetadata().put("gallery_dl_local_path", file.getAbsolutePath());
                item.getMetadata().put("source", "gallery-dl");
                item.getMetadata().put("creator", creator);

                kernel.getPipelineManager().submit(item);
                logger.info("  -> Submitted item: {}", file.getName());
            }

        } catch (Exception e) {
            logger.error("❌ GalleryDl download error", e);
        }

        logger.info("✅ gallery-dl task complete");
    }

    private void findFilesRecursive(File dir, List<File> fileList) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                findFilesRecursive(child, fileList);
            } else {
                // Ignore .json info files
                if (!child.getName().endsWith(".json")) {
                    fileList.add(child);
                }
            }
        }
    }
}
