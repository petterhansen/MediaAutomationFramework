package com.plugins.manga.internal.model;

/**
 * Individual chapter metadata.
 */
public record ChapterInfo(
    String id,
    String chapter,         // Chapter number as string (e.g. "1", "1.5", "Extra")
    String title,           // Chapter title (may be null)
    String volume,          // Volume number (may be null)
    String scanlationGroup, // Scanlation group name
    long publishDate,       // Unix timestamp
    String provider         // Which provider this came from
) {}
