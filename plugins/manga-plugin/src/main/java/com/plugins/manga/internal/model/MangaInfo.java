package com.plugins.manga.internal.model;

import java.util.List;

/**
 * Basic manga information returned from search results.
 */
public record MangaInfo(
    String id,
    String title,
    String author,
    String coverUrl,
    String description,
    List<String> tags,
    String provider   // Which provider this came from (e.g. "mangadex", "mangapill")
) {}
