package com.plugins.manga.internal.model;

import java.util.List;

/**
 * Detailed manga information including chapter list.
 */
public record MangaDetails(
    MangaInfo info,
    List<ChapterInfo> chapters
) {}
