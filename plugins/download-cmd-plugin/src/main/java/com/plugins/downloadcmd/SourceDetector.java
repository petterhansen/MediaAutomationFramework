package com.plugins.downloadcmd;

/**
 * Detects appropriate source from query using heuristics
 */
public class SourceDetector {

    /**
     * Auto-detect source from query string
     * 
     * @param query The search query (creator, tags, URL)
     * @return Detected source: "youtube", "browser", "booru", or "coomer" (default)
     */
    public static String detect(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "coomer"; // Safe default
        }

        String q = query.trim().toLowerCase();

        // 1. YouTube URL detection
        if (isYouTubeUrl(q)) {
            return "youtube";
        }

        // 2. Direct media URL detection
        if (isDirectMediaUrl(q)) {
            return "browser";
        }

        // 3. Socials detection
        if (isSocialUrl(q)) {
            return "socials";
        }

        // 4. Booru tags detection (contains spaces or underscores)
        if (looksLikeTags(q)) {
            return "booru";
        }

        // 5. Default: Creator search (Coomer/Kemono)
        return "coomer";
    }

    private static boolean isSocialUrl(String query) {
        return query.contains("instagram.com") ||
                query.contains("facebook.com") || query.contains("fb.watch") ||
                query.contains("threads.net") ||
                query.contains("twitter.com") || query.contains("x.com") ||
                query.contains("reddit.com") || query.contains("redd.it") ||
                query.contains("pinterest.com") || query.contains("pin.it");
    }

    private static boolean isYouTubeUrl(String query) {
        return query.contains("youtube.com") ||
                query.contains("youtu.be") ||
                query.contains("youtube.com/shorts/");
    }

    private static boolean isDirectMediaUrl(String query) {
        if (!query.startsWith("http")) {
            return false;
        }

        // Check for common media extensions
        String[] mediaExtensions = {
                ".jpg", ".jpeg", ".png", ".gif", ".webp", // Images
                ".mp4", ".webm", ".mkv", ".avi", ".mov" // Videos
        };

        for (String ext : mediaExtensions) {
            if (query.endsWith(ext)) {
                return true;
            }
        }

        return false;
    }

    private static boolean looksLikeTags(String query) {
        // Booru tags typically have spaces or underscores
        // Example: "pokemon pikachu" or "pokemon_pikachu"
        return query.contains(" ") || query.contains("_");
    }
}
