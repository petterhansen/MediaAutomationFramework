package com.plugins.downloadcmd;

import com.framework.api.MediaPlugin;
import com.framework.api.DownloadSourceProvider;
import com.framework.common.DownloadRequest;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Download Command Plugin - Unified /dl command with smart source detection
 */
public class DownloadCmdPlugin implements MediaPlugin, DownloadSourceProvider {
    private static final Logger logger = LoggerFactory.getLogger(DownloadCmdPlugin.class);
    private Kernel kernel;

    @Override
    public String getName() {
        return "browser";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;

        // Register /dl command
        kernel.registerCommand("/dl", this::handleDownload);

        // Register BROWSER_BATCH executor
        kernel.getQueueManager().registerExecutor("BROWSER_BATCH", new BrowserExecutor(kernel));

        // Register default browser provider for direct links
        kernel.getSourceDetectionService().registerProvider(this);

        logger.info("✅ DownloadCmd plugin enabled");
        logger.info("   Usage: /dl {amount} {query} [vid|img] [service]");
        logger.info("   Example: /dl 1 alexapearl");
        logger.info("   Example: /dl 5 coomer:alexapearl vid");
    }

    // Inner class for browser execution
    private static class BrowserExecutor implements com.framework.core.queue.TaskExecutor {
        private final Kernel kernel;

        public BrowserExecutor(Kernel kernel) {
            this.kernel = kernel;
        }

        @Override
        public void execute(QueueTask task) {
            List<String> urls = (List<String>) task.getParameter("urls");
            List<String> filenames = (List<String>) task.getParameter("filenames"); // May be null
            String filterType = task.getString("filetype");
            String folder = task.getString("folder");
            if (folder == null)
                folder = "downloads";

            if (urls == null || urls.isEmpty())
                return;

            task.setTotalItems(urls.size());

            for (int idx = 0; idx < urls.size(); idx++) {
                String url = urls.get(idx);
                // Filter check
                if (filterType != null) {
                    String lowerUrl = url.toLowerCase();
                    boolean isVideo = lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".webm")
                            || lowerUrl.endsWith(".mkv") || lowerUrl.endsWith(".mov");
                    boolean isImage = lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg")
                            || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".gif") || lowerUrl.endsWith(".webp");

                    if ("vid".equalsIgnoreCase(filterType) && !isVideo) {
                        logger.info("Skipping non-video: {}", url);
                        continue;
                    }
                    if ("img".equalsIgnoreCase(filterType) && !isImage) {
                        logger.info("Skipping non-image: {}", url);
                        continue;
                    }
                }

                // Use provided filename if available, otherwise derive from URL
                String filename = null;
                if (filenames != null && idx < filenames.size() && filenames.get(idx) != null
                        && !filenames.get(idx).isEmpty()) {
                    filename = filenames.get(idx);
                }
                if (filename == null || filename.isEmpty()) {
                    filename = url.substring(url.lastIndexOf("/") + 1);
                    if (filename.contains("?"))
                        filename = filename.substring(0, filename.indexOf("?"));
                }

                com.framework.core.pipeline.PipelineItem item = new com.framework.core.pipeline.PipelineItem(url,
                        filename, task);
                item.getMetadata().put("creator", folder);
                item.getMetadata().put("source", "browser");

                // Pass auth data (Cookies/Referer) in "headers" map as expected by
                // CoreMediaPlugin
                java.util.Map<String, String> headers = new java.util.HashMap<>();

                if (task.getParameter("cookies") != null)
                    headers.put("Cookie", (String) task.getParameter("cookies"));
                if (task.getParameter("referer") != null)
                    headers.put("Referer", (String) task.getParameter("referer"));
                if (task.getParameter("userAgent") != null)
                    headers.put("User-Agent", (String) task.getParameter("userAgent"));

                if (!headers.isEmpty())
                    item.getMetadata().put("headers", headers);

                kernel.getPipelineManager().submit(item);
            }
        }
    }

    @Override
    public void onDisable() {
        if (kernel != null) {
            kernel.getSourceDetectionService().unregisterProvider(this);
        }
        logger.info("DownloadCmd plugin disabled");
    }

    // --- DownloadSourceProvider Implementation ---

    @Override
    public boolean canHandle(String query) {
        if (query == null || !query.startsWith("http")) return false;

        String q = query.toLowerCase();
        String[] mediaExtensions = {
                ".jpg", ".jpeg", ".png", ".gif", ".webp", // Images
                ".mp4", ".webm", ".mkv", ".avi", ".mov" // Videos
        };

        for (String ext : mediaExtensions) {
            if (q.endsWith(ext) || q.contains(ext + "?")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
        QueueTask task = new QueueTask("BROWSER_BATCH");

        if (chatId != 0) {
            task.addParameter("initiatorChatId", String.valueOf(chatId));
            if (threadId != null) {
                task.addParameter("initiatorThreadId", String.valueOf(threadId));
            }
        }

        task.addParameter("urls", List.of(req.query()));
        task.addParameter("folder", "downloads");
        
        if (req.filetype() != null) {
            task.addParameter("filetype", req.filetype());
        }

        return task;
    }

    /**
     * Handle /dl command
     */
    private void handleDownload(long chatId, Integer threadId, String command, String[] args) {
        try {
            // Parse command arguments
            DownloadRequest request = CommandParser.parse(args);

            logger.info("📥 Download request: {}", request);

            // Pass directly to the SourceDetectionService which uses registered providers
            // to find a match and build the appropriate QueueTask.
            QueueTask task = kernel.getSourceDetectionService().createTask(request, chatId, threadId);
            
            kernel.getQueueManager().addTask(task);
            sendMessage(chatId, threadId, String.format("✅ Queued: %s", request));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid /dl command: {}", e.getMessage());
            sendMessage(chatId, threadId, "❌ " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling /dl command", e);
            sendMessage(chatId, threadId, "❌ Error: " + e.getMessage());
        }
    }



    /**
     * Send message to user
     */
    private void sendMessage(long chatId, Integer threadId, String message) {
        kernel.sendMessage(chatId, threadId, message);
    }
}
