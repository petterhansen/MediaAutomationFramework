package com.framework;

import com.framework.core.Kernel;
import com.framework.core.config.ConfigValidator;
import com.framework.services.database.HistoryMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for MediaAutomationFramework
 * Optimized for Raspberry Pi 5 and Windows systems
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        logger.info("🚀 Starting MediaAutomationFramework...");
        logger.info("📁 Working Directory: {}", new java.io.File(".").getAbsolutePath());
        logger.info("📄 Logs: logs/framework.log and logs/latest.log");

        try {
            // Get kernel instance
            Kernel kernel = Kernel.getInstance();

            // Validate configuration
            logger.info("🔍 Validating configuration...");
            ConfigValidator validator = new ConfigValidator();
            validator.validateAndReport(kernel.getConfigManager().getConfig());

            // Migrate legacy history if needed
            HistoryMigration.migrate(kernel);

            // Start kernel
            kernel.start();

            logger.info("✅ Framework started successfully");
            logger.info("🌐 Web Dashboard: http://localhost:6875");
            logger.info("📱 Telegram Bot: {}",
                    kernel.getConfigManager().getConfig().telegramEnabled ? "Enabled" : "Disabled");

            // Keep main thread alive
            Thread.currentThread().join();

        } catch (InterruptedException e) {
            logger.warn("⚠️  Main thread interrupted. Shutting down...");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("❌ CRITICAL FAILURE during startup", e);
            System.exit(1);
        }
    }
}