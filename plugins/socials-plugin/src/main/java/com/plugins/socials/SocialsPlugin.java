package com.plugins.socials;

import com.framework.api.MediaPlugin;
import com.framework.api.DownloadSourceProvider;
import com.framework.common.DownloadRequest;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import com.framework.core.pipeline.StageHandler;
import com.plugins.socials.internal.SocialsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Socials Plugin - Downloads media from Instagram, Facebook, Threads, Twitter, Reddit, Pinterest
 */
public class SocialsPlugin implements MediaPlugin, DownloadSourceProvider {
    private static final Logger logger = LoggerFactory.getLogger(SocialsPlugin.class);
    private Kernel kernel;
    private SocialsSource source;
    private String ytDlpPath = null;

    @Override
    public String getName() {
        return "socials";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;

        if (!checkYtDlpInstalled()) {
            logger.error("❌ yt-dlp not found! Socials plugin requires yt-dlp.");
            return;
        }

        this.source = new SocialsSource(kernel, ytDlpPath);
        kernel.getQueueManager().registerExecutor("socials_dl", source);

        // Register as a DownloadSourceProvider
        kernel.getSourceDetectionService().registerProvider(this);

        // Register Pipeline Download Handler for socials_dl=true
        kernel.getPipelineManager().registerDownloadHandler(new StageHandler<>() {
            @Override
            public boolean supports(com.framework.core.pipeline.PipelineItem item) {
                return Boolean.TRUE.equals(item.getMetadata().get("socials_dl"));
            }

            @Override
            public File process(com.framework.core.pipeline.PipelineItem item) throws Exception {
                // Delegate to source which has the download logic
                // In a real refactor, download logic might move to a shared helper
                // For now, we reuse the logic in SocialsSource or implement here.
                // Actually adhering to pattern: Source creates item, Pipeline downloads.
                // But since yt-dlp does the download, we wrap it here.
                return source.downloadWithYtDlp(item);
            }
        });

        logger.info("✅ Socials Plugin enabled (Insta, FB, Threads, X, Reddit, Pinterest)");
    }

    @Override
    public void onDisable() {
        if (kernel != null) {
            kernel.getSourceDetectionService().unregisterProvider(this);
        }
        logger.info("Socials Plugin disabled");
    }

    // --- DownloadSourceProvider Implementation ---

    @Override
    public boolean canHandle(String query) {
        if (query == null) return false;
        String q = query.toLowerCase();
        return q.contains("instagram.com") ||
               q.contains("facebook.com") || q.contains("fb.watch") ||
               q.contains("threads.net") ||
               q.contains("twitter.com") || q.contains("x.com") ||
               q.contains("reddit.com") || q.contains("redd.it") ||
               q.contains("pinterest.com") || q.contains("pin.it");
    }

    @Override
    public QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
        QueueTask task = new QueueTask("socials_dl");
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

    private boolean checkYtDlpInstalled() {
        // Priority 1: Check local binary paths (including Linux Venv)
        File[] localPaths = {
            new File("tools/yt-dlp.exe"),
            new File("tools/yt-dlp"),
            new File("tools/venv/bin/yt-dlp")
        };

        for (File path : localPaths) {
            if (path.exists()) {
                ytDlpPath = path.getAbsolutePath();
                logger.info("🎯 Found yt-dlp binary: {}", ytDlpPath);
                return true;
            }
        }

        // Priority 2: Check system PATH
        try {
            Process process = new ProcessBuilder("yt-dlp", "--version").start();
            if (process.waitFor() == 0) {
                ytDlpPath = "yt-dlp";
                logger.info("🎯 Found yt-dlp in system PATH");
                return true;
            }
        } catch (Exception e) {}

        return false;
    }
}
