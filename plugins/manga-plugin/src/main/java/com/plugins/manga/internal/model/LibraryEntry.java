package com.plugins.manga.internal.model;

/**
 * A manga entry in the user's local library.
 */
public record LibraryEntry(
    String mangaId,
    String title,
    String author,
    String coverUrl,
    String provider,
    long addedDate,
    int downloadedChapters,
    int totalChapters,
    String lastReadChapter,    // Chapter number of last read chapter (null if unread)
    String localCoverPath      // Path to a local chapter directory for cover extraction
) {}
