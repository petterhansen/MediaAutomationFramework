package com.plugins.manga.internal;

import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import com.plugins.manga.internal.model.ChapterInfo;
import com.plugins.manga.internal.model.MangaDetails;
import com.plugins.manga.internal.model.MangaInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * TaskExecutor for manga download tasks submitted via the QueueManager.
 * Handles batch chapter downloads triggered by commands or the web UI.
 */
public class MangaSource {
    private static final Logger logger = LoggerFactory.getLogger(MangaSource.class);

    private final Kernel kernel;
    private final MangaApiService apiService;
    private final MangaChapterDownloader downloader;
    private final MangaLibrary library;

    public MangaSource(Kernel kernel, MangaApiService apiService, MangaChapterDownloader downloader, MangaLibrary library) {
        this.kernel = kernel;
        this.apiService = apiService;
        this.downloader = downloader;
        this.library = library;
    }

    /**
     * Execute a manga download task.
     * Expected task metadata:
     * - "mangaId": provider-specific manga ID
     * - "provider": provider name (e.g. "mangadex", "mangapill")
     * - "chapterFrom": start chapter number (optional, defaults to first)
     * - "chapterTo": end chapter number (optional, defaults to last)
     */
    public void execute(Map<String, Object> taskParams) {
        String mangaId = (String) taskParams.get("mangaId");
        String provider = (String) taskParams.get("provider");
        String chapterFrom = (String) taskParams.getOrDefault("chapterFrom", null);
        String chapterTo = (String) taskParams.getOrDefault("chapterTo", null);

        if (mangaId == null || provider == null) {
            logger.error("❌ Manga download task missing mangaId or provider");
            return;
        }

        logger.info("📥 Starting manga download: mangaId={}, provider={}, chapters={}-{}",
                mangaId, provider, chapterFrom != null ? chapterFrom : "first", chapterTo != null ? chapterTo : "last");

        try {
            // Get manga details
            MangaDetails details = apiService.getDetails(provider, mangaId);
            if (details == null) {
                logger.error("❌ Could not fetch manga details for: {}", mangaId);
                return;
            }

            MangaInfo info = details.info();
            String slugName = info.title().replaceAll("[^a-zA-Z0-9 ]", "").trim().replaceAll("\\s+", "-").toLowerCase();

            // Ensure manga is in library
            if (!library.isInLibrary(mangaId, provider)) {
                library.addToLibrary(info);
            }

            // Filter chapters by range or specific IDs
            List<ChapterInfo> chapters = details.chapters();
            List<String> targetChapterIds = (List<String>) taskParams.get("chapterIds");

            if (targetChapterIds != null && !targetChapterIds.isEmpty()) {
                // Filter by specific IDs (for single chapter download)
                chapters = chapters.stream()
                        .filter(ch -> targetChapterIds.contains(ch.id()))
                        .toList();
            } else if (chapterFrom != null || chapterTo != null) {
                // Filter by range
                double from = chapterFrom != null ? parseChapterNum(chapterFrom) : 0;
                double to = chapterTo != null ? parseChapterNum(chapterTo) : Double.MAX_VALUE;
                chapters = chapters.stream()
                        .filter(ch -> {
                            double num = parseChapterNum(ch.chapter());
                            return num >= from && num <= to;
                        })
                        .toList();
            }

            logger.info("📚 Downloading {} chapters of '{}'", chapters.size(), info.title());

            int downloaded = 0;
            for (ChapterInfo chapter : chapters) {
                // Skip already downloaded chapters
                if (downloader.isChapterCached(slugName, chapter.chapter())) {
                    logger.debug("⏭ Chapter {} already cached, skipping", chapter.chapter());
                    downloaded++;
                    continue;
                }

                // Fetch page URLs
                List<String> pageUrls = apiService.getChapterPages(provider, chapter.id());
                if (pageUrls.isEmpty()) {
                    logger.warn("⚠ No pages found for chapter {}", chapter.chapter());
                    continue;
                }

                // Download
                File chapterDir = downloader.downloadChapter(slugName, chapter, pageUrls);
                if (chapterDir != null) {
                    library.markChapterDownloaded(mangaId, chapter.id(), chapter.chapter(), provider, chapterDir.getAbsolutePath());
                    downloaded++;
                }

                // Rate limiting between chapters
                Thread.sleep(500);
            }

            logger.info("✅ Manga download complete: {} — {}/{} chapters downloaded", info.title(), downloaded, chapters.size());

        } catch (Exception e) {
            logger.error("❌ Manga download failed for: {}", mangaId, e);
        }
    }

    private double parseChapterNum(String chapter) {
        try {
            return Double.parseDouble(chapter);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
