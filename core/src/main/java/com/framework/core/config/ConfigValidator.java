package com.framework.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ConfigValidator - Validates configuration on startup.
 * Prevents runtime errors by catching configuration issues early.
 */
public class ConfigValidator {
    private static final Logger logger = LoggerFactory.getLogger(ConfigValidator.class);

    public static class ValidationError {
        public final String message;
        public final String severity; // ERROR, WARNING

        public ValidationError(String message, String severity) {
            this.message = message;
            this.severity = severity;
        }

        @Override
        public String toString() {
            return "[" + severity + "] " + message;
        }
    }

    /**
     * Validate configuration and return list of errors/warnings
     */
    public List<ValidationError> validate(Configuration config) {
        List<ValidationError> errors = new ArrayList<>();

        // 1. Check FFmpeg paths
        validateFFmpeg(config, errors);

        // 2. Check directory paths
        validateDirectories(config, errors);

        // 3. Check Telegram configuration
        validateTelegram(config, errors);

        // 4. Check disk space
        validateDiskSpace(config, errors);

        return errors;
    }

    private void validateFFmpeg(Configuration config, List<ValidationError> errors) {
        String ffmpegPath = config.getPluginSetting("CoreMedia", "ffmpeg_path", "");
        String ffprobePath = config.getPluginSetting("CoreMedia", "ffprobe_path", "");

        if (!ffmpegPath.isEmpty() && !new File(ffmpegPath).exists()) {
            errors.add(new ValidationError(
                    "FFmpeg not found at: " + ffmpegPath + " - media processing will fail",
                    "ERROR"));
        }

        if (!ffprobePath.isEmpty() && !new File(ffprobePath).exists()) {
            errors.add(new ValidationError(
                    "FFprobe not found at: " + ffprobePath + " - video analysis will fail",
                    "WARNING"));
        }
    }

    private void validateDirectories(Configuration config, List<ValidationError> errors) {
        // Check download path
        if (config.downloadPath != null && !config.downloadPath.isEmpty()) {
            File dlPath = new File(config.downloadPath);
            if (!dlPath.exists()) {
                if (!dlPath.mkdirs()) {
                    errors.add(new ValidationError(
                            "Cannot create download directory: " + config.downloadPath,
                            "ERROR"));
                } else {
                    logger.info("✅ Created download directory: {}", config.downloadPath);
                }
            }
        }

        // Check temp directory
        if (config.tempDir != null && !config.tempDir.isEmpty()) {
            File tempPath = new File(config.tempDir);
            if (!tempPath.exists() && !tempPath.mkdirs()) {
                errors.add(new ValidationError(
                        "Cannot create temp directory: " + config.tempDir,
                        "ERROR"));
            }
        }
    }

    private void validateTelegram(Configuration config, List<ValidationError> errors) {
        if (config.telegramEnabled) {
            if (config.telegramToken == null || config.telegramToken.isEmpty()) {
                errors.add(new ValidationError(
                        "Telegram enabled but no token configured - bot will not work",
                        "ERROR"));
            }

            if (config.telegramAdminId == null || config.telegramAdminId.isEmpty()) {
                errors.add(new ValidationError(
                        "Telegram enabled but no admin ID configured - uploads may fail",
                        "WARNING"));
            }
        }
    }

    private void validateDiskSpace(Configuration config, List<ValidationError> errors) {
        File root = new File(".");
        long freeGB = root.getFreeSpace() / 1024 / 1024 / 1024;

        if (freeGB < 5) {
            errors.add(new ValidationError(
                    String.format("Low disk space: only %d GB free - consider cleanup", freeGB),
                    "WARNING"));
        } else if (freeGB < 2) {
            errors.add(new ValidationError(
                    String.format("CRITICAL: Only %d GB free - downloads may fail!", freeGB),
                    "ERROR"));
        }
    }

    /**
     * Validate and report errors to logger.
     * Throws RuntimeException if critical errors found.
     */
    public void validateAndReport(Configuration config) {
        List<ValidationError> errors = validate(config);

        int errorCount = 0;
        int warningCount = 0;

        for (ValidationError error : errors) {
            if (error.severity.equals("ERROR")) {
                logger.error("❌ Config Error: {}", error.message);
                errorCount++;
            } else {
                logger.warn("⚠️  Config Warning: {}", error.message);
                warningCount++;
            }
        }

        if (errorCount > 0 || warningCount > 0) {
            logger.warn("📋 Configuration validation: {} errors, {} warnings", errorCount, warningCount);
        } else {
            logger.info("✅ Configuration validation passed");
        }

        if (errorCount > 0) {
            throw new RuntimeException(
                    String.format("Configuration validation failed with %d error(s). Fix config and restart.",
                            errorCount));
        }
    }
}
