package com.plugins.manga.internal;

import com.plugins.manga.internal.model.ChapterInfo;
import com.plugins.manga.internal.model.MangaDetails;
import com.plugins.manga.internal.model.MangaInfo;

import java.util.List;

/**
 * Interface for manga source providers.
 * Each provider implements access to a specific manga aggregator site.
 */
public interface MangaProvider {

    /**
     * Provider identifier (e.g. "mangadex", "mangapill").
     */
    String getName();

    /**
     * Human-readable display name (e.g. "MangaDex", "MangaPill").
     */
    String getDisplayName();

    /**
     * Search for manga by title query.
     *
     * @param query Search term
     * @param limit Max results to return
     * @return List of matching manga
     */
    List<MangaInfo> search(String query, int limit);

    /**
     * Get full manga details including chapter list.
     *
     * @param mangaId Provider-specific manga ID
     * @return Manga details with chapters, or null if not found
     */
    MangaDetails getDetails(String mangaId);

    /**
     * Get page image URLs for a specific chapter.
     *
     * @param chapterId Provider-specific chapter ID
     * @return Ordered list of page image URLs
     */
    List<String> getChapterPages(String chapterId);

    /**
     * Whether this provider serves NSFW/Adult content.
     * 
     * @return true if NSFW, false otherwise (default).
     */
    default boolean isNsfw() {
        return false;
    }
}
