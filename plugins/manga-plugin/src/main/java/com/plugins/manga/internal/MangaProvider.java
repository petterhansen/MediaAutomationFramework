package com.plugins.manga.internal;

import com.plugins.manga.internal.model.ChapterInfo;
import com.plugins.manga.internal.model.MangaDetails;
import com.plugins.manga.internal.model.MangaInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
     * Get a list of popular manga (e.g. trending or most followed).
     *
     * @param limit Max results
     * @return List of popular manga
     */
    default List<MangaInfo> getPopular(int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * Whether this provider serves NSFW/Adult content.
     * 
     * @return true if NSFW, false otherwise (default).
     */
    default boolean isNsfw() {
        return false;
    }

    /**
     * Get a list of available genres/tags for this provider.
     * 
     * @return List of genre names
     */
    default List<String> getGenres() {
        return java.util.Collections.emptyList();
    }

    /**
     * Get manga belonging to a specific genre.
     * 
     * @param genre Category name
     * @param limit Max results
     * @param page  Page number (1-indexed)
     * @param sort  Sort order (e.g. "name", "latest")
     * @return List of matching manga
     */
    default List<MangaInfo> getByGenre(String genre, int limit, int page, String sort) {
        return getByGenre(genre, limit, page);
    }

    /**
     * @deprecated Use the version with sort parameter
     */
    @Deprecated
    default List<MangaInfo> getByGenre(String genre, int limit, int page) {
        return java.util.Collections.emptyList();
    }

    /**
     * Get a random manga from this provider.
     *
     * @return Random manga info, or null if not supported
     */
    default MangaInfo getRandom() {
        return null;
    }

    /**
     * Get multiple random manga from this provider.
     */
    default List<MangaInfo> getRandom(int limit) {
        List<CompletableFuture<MangaInfo>> futures = IntStream.range(0, limit)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return getRandom();
                    } catch (Exception e) {
                        return null;
                    }
                }))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(cf -> {
                            try {
                                return cf.join();
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList()))
                .join();
    }
}
