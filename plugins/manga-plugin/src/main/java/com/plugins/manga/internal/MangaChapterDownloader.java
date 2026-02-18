package com.plugins.manga.internal;

import com.plugins.manga.internal.model.ChapterInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Downloads manga chapter page images to the local cache.
 */
public class MangaChapterDownloader {
    private static final Logger logger = LoggerFactory.getLogger(MangaChapterDownloader.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private final Path cacheRoot;

    public MangaChapterDownloader(String cacheBasePath) {
        this.cacheRoot = Path.of(cacheBasePath, "manga");
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException e) {
            logger.error("Failed to create manga cache directory: {}", cacheRoot, e);
        }
    }

    /**
     * Download all pages for a chapter into the local cache.
     *
     * @param mangaSlug Sanitized manga title for folder name
     * @param chapter   Chapter metadata
     * @param pageUrls  List of page image URLs
     * @return The directory containing the downloaded pages, or null on failure
     */
    public File downloadChapter(String mangaSlug, ChapterInfo chapter, List<String> pageUrls) {
        String chapterDir = "chapter-" + sanitize(chapter.chapter());
        Path chapterPath = cacheRoot.resolve(sanitize(mangaSlug)).resolve(chapterDir);

        try {
            Files.createDirectories(chapterPath);
        } catch (IOException e) {
            logger.error("Failed to create chapter directory: {}", chapterPath, e);
            return null;
        }

        logger.info("📥 Downloading {} pages for {} Chapter {}...", pageUrls.size(), mangaSlug, chapter.chapter());

        int downloaded = 0;
        for (int i = 0; i < pageUrls.size(); i++) {
            String pageUrl = pageUrls.get(i);
            String extension = guessExtension(pageUrl);
            String fileName = String.format("page-%03d%s", i + 1, extension);
            Path pagePath = chapterPath.resolve(fileName);

            // Skip if already downloaded
            if (Files.exists(pagePath) && Files.isRegularFile(pagePath)) {
                try {
                    if (Files.size(pagePath) > 0) {
                        downloaded++;
                        continue;
                    }
                } catch (IOException ignored) {}
            }

            if (downloadFile(pageUrl, pagePath, chapter.provider())) {
                downloaded++;
            } else {
                logger.warn("Failed to download page {} of chapter {}", i + 1, chapter.chapter());
            }

            // Small delay between page downloads to be polite
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        // Write metadata sidecar
        writeChapterMeta(chapterPath, chapter, pageUrls.size(), downloaded);

        logger.info("✅ Downloaded {}/{} pages for {} Chapter {}", downloaded, pageUrls.size(), mangaSlug, chapter.chapter());
        return chapterPath.toFile();
    }

    /**
     * Check if a chapter is already cached (all pages present).
     */
    public boolean isChapterCached(String mangaSlug, String chapterNum) {
        String chapterDir = "chapter-" + sanitize(chapterNum);
        Path chapterPath = cacheRoot.resolve(sanitize(mangaSlug)).resolve(chapterDir);
        if (!Files.exists(chapterPath)) return false;

        // Check if metadata file exists (indicates complete download)
        return Files.exists(chapterPath.resolve("chapter.json"));
    }

    /**
     * Get the local path for a cached chapter.
     */
    public File getChapterCachePath(String mangaSlug, String chapterNum) {
        String chapterDir = "chapter-" + sanitize(chapterNum);
        return cacheRoot.resolve(sanitize(mangaSlug)).resolve(chapterDir).toFile();
    }

    /**
     * List local page files for a cached chapter (sorted by name).
     */
    public File[] getLocalPages(File chapterDir) {
        if (chapterDir == null || !chapterDir.isDirectory()) return new File[0];
        File[] pages = chapterDir.listFiles((dir, name) ->
                name.startsWith("page-") && (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp")));
        if (pages != null) {
            java.util.Arrays.sort(pages);
        }
        return pages != null ? pages : new File[0];
    }

    /**
     * Delete a manga and all its downloaded chapters from the cache.
     */
    public void deleteManga(String mangaSlug) {
        File mangaDir = cacheRoot.resolve(sanitize(mangaSlug)).toFile();
        if (mangaDir.exists()) {
            deleteRecursive(mangaDir);
            logger.info("Deleted manga directory: {}", mangaDir.getAbsolutePath());
        }
    }

    /**
     * Delete a specific chapter from the cache.
     */
    public void deleteChapter(String mangaSlug, String chapterNum) {
        File chapterDir = getChapterCachePath(mangaSlug, chapterNum);
        if (chapterDir.exists()) {
            deleteRecursive(chapterDir);
            logger.info("Deleted chapter directory: {}", chapterDir.getAbsolutePath());
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // --- Internal helpers ---

    private boolean downloadFile(String url, Path target, String provider) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            
            // Provider-specific headers
            if ("mangadex".equals(provider)) {
                // MangaDex requires custom UA
                conn.setRequestProperty("User-Agent", "MediaAutomationFramework/1.0 MangaPlugin");
                conn.setRequestProperty("Referer", "https://mangadex.org/");
            } else if ("mangapill".equals(provider)) {
                // MangaPill requires browser UA + Referer
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Referer", "https://mangapill.com/");
            } else {
                conn.setRequestProperty("User-Agent", USER_AGENT);
            }

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status != 200) {
                logger.warn("❌ Download failed (HTTP {}) for {} [provider: {}]", status, url, provider);
                // Read error stream
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        String error = new String(es.readAllBytes());
                        logger.debug("Error body: {}", error);
                    }
                }
                return false;
            }

            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            logger.error("❌ Exception downloading {}: {}", url, e.getMessage());
            return false;
        }
    }

    private void writeChapterMeta(Path chapterPath, ChapterInfo chapter, int totalPages, int downloadedPages) {
        try {
            String meta = String.format(
                    "{\"chapterId\":\"%s\",\"chapterNum\":\"%s\",\"title\":\"%s\",\"totalPages\":%d,\"downloadedPages\":%d,\"provider\":\"%s\"}",
                    chapter.id(), chapter.chapter(),
                    chapter.title() != null ? chapter.title().replace("\"", "\\\"") : "",
                    totalPages, downloadedPages, chapter.provider()
            );
            Files.writeString(chapterPath.resolve("chapter.json"), meta);
        } catch (IOException e) {
            logger.warn("Failed to write chapter metadata", e);
        }
    }

    private String guessExtension(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".webp")) return ".webp";
        if (lower.contains(".gif")) return ".gif";
        return ".jpg"; // Default
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }
}
