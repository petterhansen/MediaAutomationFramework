package com.plugins.gallerydl;

import com.framework.api.DownloadSourceProvider;
import com.framework.api.MediaPlugin;
import com.framework.common.DownloadRequest;
import com.framework.core.Kernel;
import com.framework.core.pipeline.StageHandler;
import com.framework.core.queue.QueueTask;
import com.plugins.gallerydl.internal.GalleryDlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Gallery-dl integration plugin.
 * Acts as the absolute fallback for any URL that gallery-dl supports.
 */
public class GalleryDlPlugin implements MediaPlugin, DownloadSourceProvider {
    private static final Logger logger = LoggerFactory.getLogger(GalleryDlPlugin.class);
    private Kernel kernel;
    private GalleryDlSource source;
    private String galleryDlPath;

    @Override
    public String getName() {
        return "gallery-dl";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;

        if (!checkGalleryDlInstalled()) {
            logger.error("❌ gallery-dl not found! Please install it (e.g., pip install gallery-dl).");
            return;
        }

        this.source = new GalleryDlSource(kernel, galleryDlPath);
        kernel.getQueueManager().registerExecutor("GALLERYDL_BATCH", source);

        // Register as the DEFAULT fallback provider
        kernel.getSourceDetectionService().registerDefaultProvider(this);

        // Also register as a normal provider if someone explicitly uses "gallery-dl:"
        kernel.getSourceDetectionService().registerProvider(this);

        // Register Pipeline Download Handler
        // Bypasses actual HTTP downloading since GalleryDlSource already downloaded the files locally
        kernel.getPipelineManager().registerDownloadHandler(new StageHandler<>() {
            @Override
            public boolean supports(com.framework.core.pipeline.PipelineItem item) {
                return Boolean.TRUE.equals(item.getMetadata().get("gallery_dl_downloaded"));
            }

            @Override
            public File process(com.framework.core.pipeline.PipelineItem item) throws Exception {
                String path = (String) item.getMetadata().get("gallery_dl_local_path");
                if (path == null) {
                    throw new RuntimeException("Missing gallery_dl_local_path in item metadata");
                }
                File localFile = new File(path);
                if (!localFile.exists()) {
                    throw new RuntimeException("Gallery-dl referenced file does not exist: " + path);
                }
                return localFile; // Skip download, just return the already downloaded file!
            }
        });

        // Register /gdl command for explicit usage
        kernel.registerCommand("/gdl", this::handleGalleryDl);

        logger.info("✅ gallery-dl plugin enabled");
    }

    @Override
    public void onDisable() {
        if (kernel != null) {
            kernel.getSourceDetectionService().unregisterProvider(this);
        }
        logger.info("gallery-dl plugin disabled");
    }

    // --- DownloadSourceProvider Implementation ---

    @Override
    public boolean canHandle(String query) {
        // ALWAYS return false for auto-detect!
        // Gallery-dl is explicitly registered as the 'defaultProvider' in SourceDetectionService.
        // It will automatically be used if no other plugin returns true for a URL.
        // Returning true here causes it to aggressively intercept URLs meant for other plugins 
        // that loaded later in the boot sequence (like TikTok, Socials, or YouTube).
        return false;
    }

    @Override
    public QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
        QueueTask task = new QueueTask("GALLERYDL_BATCH");
        task.addParameter("query", req.query());
        task.addParameter("amount", req.amount() > 0 ? req.amount() : 1);
        
        if (req.filetype() != null) task.addParameter("filetype", req.filetype());
        if (req.service() != null) task.addParameter("service", req.service());
        if (req.folder() != null) task.addParameter("folder", req.folder());

        if (chatId != 0) {
            task.addParameter("initiatorChatId", String.valueOf(chatId));
            if (threadId != null) {
                task.addParameter("initiatorThreadId", String.valueOf(threadId));
            }
        }
        return task;
    }

    private void handleGalleryDl(long chatId, Integer threadId, String command, String[] args) {
        if (args.length < 1) {
            kernel.sendMessage(chatId, threadId, "❌ Usage: /gdl <URL>");
            return;
        }
        String url = args[0];
        QueueTask task = createTask(new DownloadRequest(1, "gallery-dl", url, null, null, null), chatId, threadId);
        kernel.getQueueManager().addTask(task);
        kernel.sendMessage(chatId, threadId, "📦 gallery-dl task queued: " + url);
    }

    private boolean checkGalleryDlInstalled() {
        // Priority 1: Check tools directory (direct binaries)
        File[] localPaths = {
            new File("tools/gallery-dl.exe"),
            new File("tools/gallery-dl"),
            new File("tools/venv/bin/gallery-dl") // Linux Venv Support
        };

        for (File path : localPaths) {
            if (path.exists()) {
                galleryDlPath = path.getAbsolutePath();
                logger.info("🎯 Found gallery-dl binary: {}", galleryDlPath);
                return true;
            }
        }

        // Priority 2: System PATH
        try {
            Process process = new ProcessBuilder("gallery-dl", "--version").start();
            if (process.waitFor() == 0) {
                galleryDlPath = "gallery-dl";
                logger.info("🎯 Found gallery-dl in system PATH");
                return true;
            }
        } catch (Exception e) {}

        return false;
    }
}
