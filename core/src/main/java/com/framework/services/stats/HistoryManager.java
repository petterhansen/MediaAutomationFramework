package com.framework.services.stats;

import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * HistoryManager - Tracks downloaded and processed media using database.
 * Migrated from file-based to database for better querying and performance on
 * Raspberry Pi.
 */
public class HistoryManager {
    private static final Logger logger = LoggerFactory.getLogger(HistoryManager.class);
    private final Kernel kernel;

    public HistoryManager(Kernel kernel) {
        this.kernel = kernel;
        logger.debug("HistoryManager initialized with database backend");
    }

    /**
     * Check if a URL has already been downloaded
     */
    public boolean isDownloaded(String url) {
        if (url == null || url.isEmpty())
            return false;
        return kernel.getDatabaseService().hasDownloaded(url);
    }

    /**
     * Check if a file has been processed for a creator (legacy file-based method
     * name)
     */
    public boolean isProcessed(String creatorName, String fileName) {
        if (creatorName == null || fileName == null)
            return false;

        // Check if any item from this creator with this filename exists
        return kernel.getDatabaseService().getJdbi().withHandle(handle -> {
            Integer count = handle.createQuery(
                    "SELECT COUNT(*) FROM media_items WHERE creator = ? AND file_name = ?")
                    .bind(0, creatorName)
                    .bind(1, fileName)
                    .mapTo(Integer.class)
                    .one();
            return count > 0;
        });
    }

    /**
     * Mark a download (called from pipeline)
     */
    public void markDownloaded(String url, String creator, String source) {
        if (url == null || url.isEmpty())
            return;
        kernel.getDatabaseService().markDownloaded(url, creator, source);
        logger.debug("Marked as downloaded: {} from {}", url, creator);
    }

    /**
     * Mark as processed (legacy file-based method signature)
     */
    public synchronized void markAsProcessed(String creatorName, String fileName) {
        if (creatorName == null || fileName == null)
            return;

        // Update or insert record
        kernel.getDatabaseService().getJdbi().useHandle(handle -> {
            handle.createUpdate("""
                        MERGE INTO media_items (creator, file_name, status, processed_at, url)
                        KEY(creator, file_name)
                        VALUES (?, ?, 'PROCESSED', CURRENT_TIMESTAMP, NULL)
                    """)
                    .bind(0, creatorName)
                    .bind(1, fileName)
                    .execute();
        });

        logger.debug("Marked as processed: {} for {}", fileName, creatorName);
    }

    /**
     * Get download count for a specific creator
     */
    public int getHistorySize(String creatorName) {
        if (creatorName == null)
            return 0;
        return kernel.getDatabaseService().getDownloadCount(creatorName);
    }

    /**
     * Get last N entries for a creator
     */
    public List<String> getLastEntries(String creatorName, int limit) {
        if (creatorName == null)
            return Collections.emptyList();

        return kernel.getDatabaseService().getJdbi().withHandle(handle -> {
            return handle.createQuery("""
                        SELECT file_name FROM media_items
                        WHERE creator = ?
                        ORDER BY downloaded_at DESC
                        LIMIT ?
                    """)
                    .bind(0, creatorName)
                    .bind(1, limit)
                    .mapTo(String.class)
                    .list();
        });
    }

    /**
     * Delete history for a creator (marks as deleted in DB)
     */
    public boolean deleteHistory(String creatorName) {
        if (creatorName == null)
            return false;

        kernel.getDatabaseService().getJdbi().useHandle(handle -> {
            handle.createUpdate("DELETE FROM media_items WHERE creator = ?")
                    .bind(0, creatorName)
                    .execute();
        });

        logger.info("Deleted history for creator: {}", creatorName);
        return true;
    }

    /**
     * Get total download count across all creators
     */
    public long getTotalDownloads() {
        return kernel.getDatabaseService().getTotalDownloads();
    }

    /**
     * Get list of all tracked creators
     */
    public List<String> getAllCreators() {
        return kernel.getDatabaseService().getJdbi().withHandle(handle -> {
            return handle
                    .createQuery("SELECT DISTINCT creator FROM media_items WHERE creator IS NOT NULL ORDER BY creator")
                    .mapTo(String.class)
                    .list();
        });
    }
}