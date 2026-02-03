package com.plugins.downloadcmd;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Download Command Plugin - Unified /dl command with smart source detection
 */
public class DownloadCmdPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(DownloadCmdPlugin.class);
    private Kernel kernel;

    @Override
    public String getName() {
        return "DownloadCmd";
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
        
        logger.info("✅ DownloadCmd plugin enabled");
        logger.info("   Usage: /dl {amount} {query} [vid|img] [service]");
        logger.info("   Example: /dl 1 alexapearl");
        logger.info("   Example: /dl 5 coomer:alexapearl vid");
    }

    @Override
    public void onDisable() {
        logger.info("DownloadCmd plugin disabled");
    }

    /**
     * Handle /dl command
     */
    private void handleDownload(long chatId, Integer threadId, String command, String[] args) {
        try {
            // Parse command arguments
            DownloadRequest request = CommandParser.parse(args);
            
            logger.info("📥 Download request: {}", request);
            
            // Auto-detect source if not specified
            if (request.source() == null) {
                String detectedSource = SourceDetector.detect(request.query());
                logger.info("🔍 Auto-detected source: {}", detectedSource);
                
                // Create new request with detected source
                request = new DownloadRequest(
                    request.amount(),
                    detectedSource,
                    request.query(),
                    request.filetype(),
                    request.service()
                );
            } else {
                // Normalize source aliases
                String normalizedSource = normalizeSource(request.source());
                request = new DownloadRequest(
                    request.amount(),
                    normalizedSource,
                    request.query(),
                    request.filetype(),
                    request.service()
                );
            }
            
            // Execute request
            executeRequest(chatId, threadId, request);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid /dl command: {}", e.getMessage());
            sendMessage(chatId, threadId, "❌ " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling /dl command", e);
            sendMessage(chatId, threadId, "❌ Error: " + e.getMessage());
        }
    }

    /**
     * Execute request (no fallback)
     */
    private void executeRequest(long chatId, Integer threadId, DownloadRequest request) {
        QueueTask task = createTask(request, chatId, threadId);
        kernel.getQueueManager().addTask(task);
        
        sendMessage(chatId, threadId, String.format("✅ Queued: %s", request));
    }

    /**
     * Create QueueTask from DownloadRequest
     */
    private QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
        String taskType;
        
        // Determine task type based on source
        switch (req.source()) {
            case "coomer":
            case "kemono":
                taskType = "SEARCH_BATCH"; // PartyPlugin registers this task type
                break;
            case "youtube":
                taskType = "youtube";
                break;
            case "booru":
                taskType = "BOORU_BATCH";
                break;
            case "browser":
                taskType = "BROWSER_BATCH";
                break;
            default:
                throw new IllegalArgumentException("Unknown source: " + req.source());
        }
        
        QueueTask task = new QueueTask(taskType);
        
        // Store initiator info for dynamic routing (ALL sources)
        if (chatId != 0) {
            task.addParameter("initiatorChatId", String.valueOf(chatId));
            if (threadId != null) {
                task.addParameter("initiatorThreadId", String.valueOf(threadId));
            }
        }
        
        switch (req.source()) {
            case "coomer":
            case "kemono":
                // Party plugin format
                task.addParameter("query", req.query());
                task.addParameter("amount", req.amount());
                task.addParameter("source", req.source());
                
                // Add filters
                if (req.filetype() != null) {
                    task.addParameter("filetype", req.filetype());
                }
                if (req.service() != null) {
                    task.addParameter("service", req.service());
                }
                break;

            case "youtube":
                // YouTube plugin format
                task.addParameter("type", "video");
                task.addParameter("query", req.query());
                task.addParameter("amount", req.amount());
                break;

            case "booru":
                // Booru plugin format
                task.addParameter("tags", req.query());
                task.addParameter("amount", req.amount());
                break;

            case "browser":
                // Browser source format
                task.addParameter("urls", List.of(req.query()));
                task.addParameter("folder", "downloads");
                break;

            default:
                throw new IllegalArgumentException("Unknown source: " + req.source());
        }
        
        return task;
    }

    /**
     * Normalize source aliases
     */
    private String normalizeSource(String source) {
        return switch (source) {
            case "yt" -> "youtube";
            case "r34" -> "booru";
            default -> source;
        };
    }

    /**
     * Send message to user
     */
    private void sendMessage(long chatId, Integer threadId, String message) {
        kernel.sendMessage(chatId, threadId, message);
    }
}
