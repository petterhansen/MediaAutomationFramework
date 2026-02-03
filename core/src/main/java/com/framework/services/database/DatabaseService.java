package com.framework.services.database;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.h2.tools.Server;
import java.io.File;
import java.sql.SQLException;

/**
 * DatabaseService - H2 embedded database for media tracking and history.
 * Optimized for Raspberry Pi 5 with minimal memory footprint.
 */
public class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);
    private final Jdbi jdbi;
    private final String dbPath;
    private Server webServer;

    public DatabaseService(String dbPath) {
        this.dbPath = dbPath;

        // Ensure directory exists
        new File(dbPath).getParentFile().mkdirs();

        // H2 connection with optimized settings for Raspberry Pi
        // Use ./ prefix for explicit relative paths (required by H2 2.2.224+)
        String url = "jdbc:h2:./" + dbPath +
                ";MODE=MySQL" + // MySQL compatibility mode
                ";DB_CLOSE_DELAY=-1" + // Keep DB open
                ";CACHE_SIZE=8192" + // 8MB cache (light for Pi)
                ";DATABASE_TO_UPPER=FALSE" + // Case-sensitive names
                ";AUTO_SERVER=TRUE"; // Allow multiple connections

        this.jdbi = Jdbi.create(url);

        // Start H2 Web Console
        try {
            this.webServer = Server.createWebServer("-web", "-webAllowOthers", "-webPort", "8082").start();
            logger.info("🌐 Database Console: http://localhost:8082");
        } catch (SQLException e) {
            logger.warn("⚠️ Could not start Database Console: {}", e.getMessage());
        }

        logger.info("🗄️ Database initialized: {}", dbPath);
        initializeSchema();
    }

    /**
     * Initialize database schema
     */
    private void initializeSchema() {
        jdbi.useHandle(handle -> {
            // Media items table
            handle.execute("""
                        CREATE TABLE IF NOT EXISTS media_items (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            url TEXT,
                            source VARCHAR(50),
                            creator VARCHAR(255),
                            file_name VARCHAR(500),
                            file_size BIGINT,
                            width INT,
                            height INT,
                            duration DOUBLE,
                            file_hash VARCHAR(64),
                            downloaded_at TIMESTAMP,
                            processed_at TIMESTAMP,
                            uploaded_at TIMESTAMP,
                            status VARCHAR(20),
                            error_message TEXT,
                            metadata TEXT,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);

            // Migration: Ensure existing tables allow NULL url
            try {
                handle.execute("ALTER TABLE media_items ALTER COLUMN url SET NULL");
            } catch (Exception e) {
                // Ignore if already nullable or other minor issue
            }

            // Indexes for common queries
            handle.execute("CREATE INDEX IF NOT EXISTS idx_url ON media_items(url)");
            handle.execute("CREATE INDEX IF NOT EXISTS idx_creator ON media_items(creator)");
            handle.execute("CREATE INDEX IF NOT EXISTS idx_downloaded_at ON media_items(downloaded_at)");
            handle.execute("CREATE INDEX IF NOT EXISTS idx_hash ON media_items(file_hash)");
            handle.execute("CREATE INDEX IF NOT EXISTS idx_status ON media_items(status)");

            // Statistics table for tracking
            handle.execute("""
                        CREATE TABLE IF NOT EXISTS download_stats (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            date DATE NOT NULL,
                            source VARCHAR(50),
                            creator VARCHAR(255),
                            count INT DEFAULT 1,
                            total_size BIGINT DEFAULT 0,
                            UNIQUE(date, source, creator)
                        )
                    """);

            logger.info("✅ Database schema initialized");
        });
    }

    /**
     * Get JDBI instance for custom queries
     */
    public Jdbi getJdbi() {
        return jdbi;
    }

    /**
     * Check if URL has already been downloaded
     */
    public boolean hasDownloaded(String url) {
        return jdbi.withHandle(handle -> {
            Integer count = handle.createQuery("SELECT COUNT(*) FROM media_items WHERE url = ?")
                    .bind(0, url)
                    .mapTo(Integer.class)
                    .one();
            return count > 0;
        });
    }

    /**
     * Mark item as downloaded
     */
    public void markDownloaded(String url, String creator, String source) {
        jdbi.useHandle(handle -> {
            handle.createUpdate("""
                        INSERT INTO media_items (url, creator, source, downloaded_at, status)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP, 'DOWNLOADED')
                    """)
                    .bind(0, url)
                    .bind(1, creator)
                    .bind(2, source)
                    .execute();
        });
    }

    /**
     * Update item with processing info
     */
    public void markProcessed(String url, String fileName, long fileSize, int width, int height, double duration) {
        jdbi.useHandle(handle -> {
            handle.createUpdate("""
                        UPDATE media_items
                        SET file_name = ?, file_size = ?, width = ?, height = ?, duration = ?,
                            processed_at = CURRENT_TIMESTAMP, status = 'PROCESSED'
                        WHERE url = ?
                    """)
                    .bind(0, fileName)
                    .bind(1, fileSize)
                    .bind(2, width)
                    .bind(3, height)
                    .bind(4, duration)
                    .bind(5, url)
                    .execute();
        });
    }

    /**
     * Mark item as uploaded
     */
    public void markUploaded(String url) {
        jdbi.useHandle(handle -> {
            handle.createUpdate("""
                        UPDATE media_items
                        SET uploaded_at = CURRENT_TIMESTAMP, status = 'UPLOADED'
                        WHERE url = ?
                    """)
                    .bind(0, url)
                    .execute();
        });
    }

    /**
     * Mark item as failed
     */
    public void markFailed(String url, String errorMessage) {
        jdbi.useHandle(handle -> {
            handle.createUpdate("""
                        UPDATE media_items
                        SET status = 'FAILED', error_message = ?
                        WHERE url = ?
                    """)
                    .bind(0, errorMessage)
                    .bind(1, url)
                    .execute();
        });
    }

    /**
     * Get download count for a creator
     */
    public int getDownloadCount(String creator) {
        return jdbi.withHandle(handle -> {
            Integer count = handle.createQuery("SELECT COUNT(*) FROM media_items WHERE creator = ?")
                    .bind(0, creator)
                    .mapTo(Integer.class)
                    .one();
            return count != null ? count : 0;
        });
    }

    /**
     * Get total downloads count
     */
    public long getTotalDownloads() {
        return jdbi.withHandle(handle -> {
            Long count = handle.createQuery("SELECT COUNT(*) FROM media_items")
                    .mapTo(Long.class)
                    .one();
            return count != null ? count : 0L;
        });
    }

    /**
     * Shutdown database cleanly
     */
    /**
     * DTO for creator statistics
     */
    public static class CreatorStatsDTO {
        public long total;
        public long images;
        public long videos;

        public CreatorStatsDTO(long total, long images, long videos) {
            this.total = total;
            this.images = images;
            this.videos = videos;
        }
    }

    /**
     * Get statistics for all creators
     */
    public java.util.Map<String, CreatorStatsDTO> getCreatorStatistics() {
        return jdbi.withHandle(handle -> {
            java.util.Map<String, CreatorStatsDTO> stats = new java.util.HashMap<>();
            handle.createQuery(
                    """
                                SELECT creator, COUNT(*) as total,
                                       SUM(CASE WHEN file_name LIKE '%.mp4' OR file_name LIKE '%.webm' THEN 1 ELSE 0 END) as videos,
                                       SUM(CASE WHEN file_name LIKE '%.jpg' OR file_name LIKE '%.png' OR file_name LIKE '%.jpeg' THEN 1 ELSE 0 END) as images
                                FROM media_items
                                WHERE creator IS NOT NULL
                                GROUP BY creator
                            """)
                    .map((rs, ctx) -> {
                        String creator = rs.getString("creator");
                        long total = rs.getLong("total");
                        long videos = rs.getLong("videos");
                        long images = rs.getLong("images");
                        return new java.util.AbstractMap.SimpleEntry<>(creator,
                                new CreatorStatsDTO(total, images, videos));
                    })
                    .list()
                    .forEach(entry -> stats.put(entry.getKey(), entry.getValue()));
            return stats;
        });
    }

    public void shutdown() {
        try {
            jdbi.useHandle(handle -> {
                handle.execute("SHUTDOWN");
            });
        } catch (Exception e) {
            logger.warn("Error shutting down database: {}", e.getMessage());
        }

        if (webServer != null && webServer.isRunning(false)) {
            webServer.stop();
        }

        logger.info("✅ Database shutdown complete");
    }
}
