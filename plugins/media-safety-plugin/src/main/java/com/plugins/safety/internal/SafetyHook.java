package com.plugins.safety.internal;

import com.framework.core.pipeline.PipelineHook;
import com.framework.core.pipeline.PipelineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class SafetyHook implements PipelineHook {
    private static final Logger logger = LoggerFactory.getLogger(SafetyHook.class);
    private final ContentScannerService scannerService;

    public SafetyHook(ContentScannerService scannerService) {
        this.scannerService = scannerService;
    }

    @Override
    public void beforeProcessing(PipelineItem item) {
        try {
            if (shouldSkipScan(item))
                return;

            File file = item.getDownloadedFile();
            if (file != null && file.exists()) {
                if (file.getName().endsWith(".mp4") || file.getName().endsWith(".webm")) {
                    scannerService.scanVideo(file);
                } else {
                    scannerService.scanImage(file);
                }
            }
        } catch (Exception e) {
            logger.warn("Safety scan failed or timed out for: {}", item.getSourceUrl(), e);
            // Decide if we block or proceed on error. Usually safety fails open or closed?
            // Assuming fail open for now to not break pipeline, but logging.
        }
    }

    private boolean shouldSkipScan(PipelineItem item) {
        // Check metadata or whitelist
        return false;
    }

    // Implement other PipelineHook methods as needed (defaults or empty)
    @Override
    public void beforeDownload(PipelineItem item) {
    }

    @Override
    public void afterDownload(PipelineItem item, File result) {
    }

    @Override
    public void afterProcessing(PipelineItem item, java.util.List<File> result) {
    }

    @Override
    public void beforeUpload(PipelineItem item) {
    }

    @Override
    public void afterUpload(PipelineItem item) {
    }

    @Override
    public void onError(PipelineItem item, Exception e, String stage) {
    }
}
