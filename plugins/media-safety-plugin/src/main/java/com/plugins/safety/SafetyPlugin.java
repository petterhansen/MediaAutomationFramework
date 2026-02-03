package com.plugins.safety;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.plugins.safety.internal.ContentScannerService;
import com.plugins.safety.internal.SafetyHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SafetyPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(SafetyPlugin.class);
    private ContentScannerService scannerService;
    private Kernel kernel;

    @Override
    public String getName() {
        return "MediaSafetyPlugin";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;
        logger.info("🛡️ Media Safety Plugin enabling...");

        try {
            // Initialize Scanner Service
            this.scannerService = new ContentScannerService(kernel);

            // Register Pipeline Hook
            kernel.getPipelineManager().registerHook(new SafetyHook(scannerService));

            logger.info("✅ Media Safety Plugin enabled.");
        } catch (Exception e) {
            logger.error("❌ Failed to enable Media Safety Plugin", e);
        }
    }

    @Override
    public void onDisable() {
        if (scannerService != null) {
            scannerService.shutdown();
        }
        logger.info("Media Safety Plugin disabled.");
    }
}
