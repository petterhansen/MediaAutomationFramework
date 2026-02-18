package com.plugins.socials;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.pipeline.StageHandler;
import com.plugins.socials.internal.SocialsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Socials Plugin - Downloads media from Instagram, Facebook, Threads, Twitter, Reddit, Pinterest
 */
public class SocialsPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(SocialsPlugin.class);
    private Kernel kernel;
    private SocialsSource source;
    private String ytDlpPath = null;

    @Override
    public String getName() {
        return "Socials";
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
        logger.info("Socials Plugin disabled");
    }

    private boolean checkYtDlpInstalled() {
        // Priority 1: Check if yt-dlp is in system PATH (e.g. installed via pip)
        try {
            Process process = new ProcessBuilder("yt-dlp", "--version").start();
            if (process.waitFor() == 0) {
                ytDlpPath = "yt-dlp";
                return true;
            }
        } catch (Exception e) {}

        // Priority 2: Check tools/yt-dlp.exe (local standalone or shim)
        File localYtDlp = new File("tools/yt-dlp.exe");
        if (localYtDlp.exists()) {
            ytDlpPath = localYtDlp.getAbsolutePath();
            return true;
        }
        
        // Priority 3: Check tools/yt-dlp (linux/mac)
        localYtDlp = new File("tools/yt-dlp");
        if (localYtDlp.exists()) {
            ytDlpPath = localYtDlp.getAbsolutePath();
            return true;
        }
        
        return false;
    }
}
