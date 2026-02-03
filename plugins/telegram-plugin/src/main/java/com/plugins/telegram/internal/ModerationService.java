package com.plugins.telegram.internal;

import com.framework.core.Kernel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ModerationService {
    private static final Logger logger = LoggerFactory.getLogger(ModerationService.class);
    private final Kernel kernel;
    private final Gson gson = new Gson();

    // Caches
    private List<String> cachedBlacklist = new ArrayList<>();
    private List<Pattern> cachedRegex = new ArrayList<>();
    private String lastBlacklistJson = "";
    private String lastRegexJson = "";
    private boolean cleanMode = false;
    private boolean initialized = false;

    public ModerationService(Kernel kernel) {
        this.kernel = kernel;
        logger.info("=== ModerationService initialized ===");
        // Force initial config load
        refreshConfig();
        initialized = true;
    }

    private void refreshConfig() {
        if (kernel.getConfigManager() == null || kernel.getConfigManager().getConfig() == null) {
            logger.warn("ConfigManager or Config is null, cannot refresh moderation config");
            return;
        }

        String blacklistJson = kernel.getConfigManager().getConfig().getPluginSetting("TelegramIntegration",
                "blacklist", "[\"scam\",\"spam\"]");
        String regexJson = kernel.getConfigManager().getConfig().getPluginSetting("TelegramIntegration",
                "regex_patterns",
                "[\"t\\\\.me\\\\/[a-zA-Z0-9_]+\", \"https?:\\\\/\\\\/(?!youtube\\\\.com|youtu\\\\.be)[^\\\\s]+\"]");

        // Update Blacklist
        if (!blacklistJson.equals(lastBlacklistJson)) {
            try {
                cachedBlacklist = gson.fromJson(blacklistJson, new TypeToken<List<String>>() {
                }.getType());
                lastBlacklistJson = blacklistJson;
                logger.info("✓ Blacklist updated: {} entries loaded", cachedBlacklist.size());
                logger.debug("  Blacklist contents: {}", cachedBlacklist);
            } catch (Exception e) {
                logger.error("✗ Failed to parse blacklist JSON: {}", blacklistJson, e);
                cachedBlacklist = new ArrayList<>();
            }
        }

        // Update Regex
        if (!regexJson.equals(lastRegexJson)) {
            try {
                List<String> patterns = gson.fromJson(regexJson, new TypeToken<List<String>>() {
                }.getType());
                cachedRegex.clear();
                int successCount = 0;
                for (String p : patterns) {
                    try {
                        cachedRegex.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
                        successCount++;
                        logger.debug("  ✓ Compiled regex: {}", p);
                    } catch (Exception e) {
                        logger.error("  ✗ Invalid regex pattern: {}", p, e);
                    }
                }
                lastRegexJson = regexJson;
                logger.info("✓ Regex patterns updated: {}/{} compiled successfully", successCount, patterns.size());
            } catch (Exception e) {
                logger.error("✗ Failed to parse regex JSON: {}", regexJson, e);
                cachedRegex.clear();
            }
        }

        // Update Clean Mode
        String cleanModeStr = kernel.getConfigManager().getConfig().getPluginSetting("TelegramIntegration",
                "clean_mode", "false");
        this.cleanMode = Boolean.parseBoolean(cleanModeStr);

        if (initialized) {
            logger.debug("Clean Mode: {}", cleanMode ? "ENABLED" : "DISABLED");
        }
    }

    public boolean isCleanMode() {
        refreshConfig(); // Ensure we have latest
        return cleanMode;
    }

    /**
     * Gets current moderation status for debugging
     */
    public String getStatus() {
        refreshConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>Moderation Status</b>\n\n");
        sb.append("🧹 Clean Mode: ").append(cleanMode ? "✅ ENABLED" : "❌ DISABLED").append("\n");
        sb.append("🚫 Blacklist: ").append(cachedBlacklist.size()).append(" words\n");
        sb.append("🛡️ Regex Patterns: ").append(cachedRegex.size()).append(" patterns\n\n");

        if (!cachedBlacklist.isEmpty()) {
            sb.append("<b>Blacklisted Words:</b>\n");
            cachedBlacklist.forEach(word -> sb.append("  • ").append(word).append("\n"));
            sb.append("\n");
        }

        if (!cachedRegex.isEmpty()) {
            sb.append("<b>Regex Patterns:</b>\n");
            cachedRegex.forEach(p -> sb.append("  • ").append(p.pattern()).append("\n"));
        }

        return sb.toString();
    }

    /**
     * Checks if text contains any violations
     */
    public String check(String text, long userId) {
        return check(text, userId, false);
    }

    /**
     * Checks if text contains any violations with optional admin bypass override
     * 
     * @param text        The message text
     * @param userId      The sender's ID
     * @param ignoreAdmin If true, admin bypass is ignored and message is checked
     * @return Reason for block, or null if allowed
     */
    /**
     * Normalizes text to catch leetspeak and character substitution bypasses
     * Examples: r@pe → rape, ch1ld → child, g0re → gore
     */
    private String normalizeText(String text) {
        String normalized = text.toLowerCase();

        // Common leetspeak/substitution mappings
        normalized = normalized.replaceAll("@", "a");
        normalized = normalized.replaceAll("4", "a");
        normalized = normalized.replaceAll("3", "e");
        normalized = normalized.replaceAll("1", "i");
        normalized = normalized.replaceAll("!", "i");
        normalized = normalized.replaceAll("0", "o");
        normalized = normalized.replaceAll("\\$", "s");
        normalized = normalized.replaceAll("5", "s");
        normalized = normalized.replaceAll("7", "t");
        normalized = normalized.replaceAll("\\+", "t");
        normalized = normalized.replaceAll("8", "b");
        normalized = normalized.replaceAll("9", "g");
        normalized = normalized.replaceAll("\\|", "l");

        // Remove common separators used to bypass detection
        normalized = normalized.replaceAll("[_\\-\\.\\s]+", "");

        return normalized;
    }

    public String check(String text, long userId, boolean ignoreAdmin) {
        // Always refresh config (lightweight check via string comparison)
        refreshConfig();

        logger.info("🔍 Moderation check for User {}: \"{}\" ({} words, {} patterns)",
                userId,
                text.length() > 50 ? text.substring(0, 50) + "..." : text,
                cachedBlacklist.size(),
                cachedRegex.size());

        // Admin check (Config based)
        String adminId = kernel.getConfigManager().getConfig().telegramAdminId;
        if (!ignoreAdmin && adminId != null && String.valueOf(userId).equals(adminId)) {
            logger.debug("  ⚡ Admin bypass for User {}", userId);
            return null; // Admin is safe
        }

        String lower = text.toLowerCase();
        String normalized = normalizeText(text);

        logger.debug("  Normalized text: \"{}\"", normalized);

        // 1. Blacklist Check (check both original and normalized)
        logger.debug("  Checking against {} blacklist words...", cachedBlacklist.size());
        for (String word : cachedBlacklist) {
            String wordLower = word.toLowerCase();
            if (lower.contains(wordLower) || normalized.contains(wordLower)) {
                logger.info("  🚫 BLOCKED: Blacklist match \"{}\" in message from User {}", word, userId);
                return "Blacklisted word: " + word;
            }
        }

        // 2. Regex Check (check both original and normalized)
        logger.debug("  Checking against {} regex patterns...", cachedRegex.size());
        for (Pattern p : cachedRegex) {
            if (p.matcher(text).find() || p.matcher(normalized).find()) {
                logger.info("  🛡️ BLOCKED: Regex match \"{}\" in message from User {}", p.pattern(), userId);
                return "Link/Pattern blocked";
            }
        }

        logger.info("  ✅ Message from User {} passed moderation", userId);
        return null; // Safe
    }
}
