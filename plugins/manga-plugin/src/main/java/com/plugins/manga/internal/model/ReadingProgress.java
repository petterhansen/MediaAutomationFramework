package com.plugins.manga.internal.model;

/**
 * Reading progress for a manga.
 */
public record ReadingProgress(
    String mangaId,
    String lastChapterId,
    String lastChapterNum,
    int lastPage,
    long updatedAt
) {}
