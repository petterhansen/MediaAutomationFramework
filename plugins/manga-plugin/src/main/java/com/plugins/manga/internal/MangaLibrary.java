package com.plugins.manga.internal;

import com.plugins.manga.internal.model.LibraryEntry;
import com.plugins.manga.internal.model.MangaInfo;
import com.plugins.manga.internal.model.ReadingProgress;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the local manga library using the framework's H2 database.
 * Handles library tracking, reading progress, and chapter download status.
 */
public class MangaLibrary {
    private static final Logger logger = LoggerFactory.getLogger(MangaLibrary.class);
    private final Jdbi jdbi;

    public MangaLibrary(Jdbi jdbi) {
        this.jdbi = jdbi;
        initializeSchema();
    }

    private void initializeSchema() {
        jdbi.useHandle(handle -> {
            // 1. Ensure Profiles Table Exists
            handle.execute("""
                        CREATE TABLE IF NOT EXISTS manga_profiles (
                            id VARCHAR(36) PRIMARY KEY,
                            name VARCHAR(255) NOT NULL,
                            created_at BIGINT
                        )
                    """);

            // 2. Ensure Default Profile
            handle.execute(
                    "MERGE INTO manga_profiles (id, name, created_at) KEY (id) VALUES ('default', 'Legacy User', ?)",
                    System.currentTimeMillis());

            // 3. Ensure Library & Chapters Tables
            handle.execute("""
                        CREATE TABLE IF NOT EXISTS manga_library (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            manga_id VARCHAR(500) NOT NULL,
                            title VARCHAR(500),
                            author VARCHAR(255),
                            cover_url TEXT,
                            provider VARCHAR(50),
                            added_date BIGINT,
                            UNIQUE(manga_id, provider)
                        )
                    """);

            handle.execute("""
                        CREATE TABLE IF NOT EXISTS manga_chapters (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            manga_id VARCHAR(500) NOT NULL,
                            chapter_id VARCHAR(500) NOT NULL,
                            chapter_num VARCHAR(20),
                            provider VARCHAR(50),
                            local_path VARCHAR(1000),
                            downloaded_at BIGINT,
                            UNIQUE(manga_id, chapter_id, provider)
                        )
                    """);

            // 4. Ensure Progress Table (New Schema)
            // If it doesn't exist, this creates it correctly.
            handle.execute("""
                        CREATE TABLE IF NOT EXISTS manga_progress (
                            manga_id VARCHAR(500) NOT NULL,
                            provider VARCHAR(50) NOT NULL,
                            profile_id VARCHAR(36) NOT NULL,
                            last_chapter_id VARCHAR(500),
                            last_chapter_num VARCHAR(20),
                            last_page INT DEFAULT 0,
                            updated_at BIGINT,
                            PRIMARY KEY(manga_id, provider, profile_id)
                        )
                    """);

            // 5. Run Migration DDLs (for legacy tables that already existed)
            try {
                // Add profile_id column if missing.
                // Setting DEFAULT 'default' automatically migrates existing rows!
                // This is safe to run even if column exists (IF NOT EXISTS).
                handle.execute(
                        "ALTER TABLE manga_progress ADD COLUMN IF NOT EXISTS profile_id VARCHAR(36) NOT NULL DEFAULT 'default'");

                // Force update of Primary Key to ensure it matches the new schema.
                // The previous check using INFORMATION_SCHEMA was unreliable.
                // Running this on every startup is acceptable for the expected dataset size.
                try {
                    // 1. Drop existing Primary Key (likely (manga_id, provider) or already correct)
                    // We ignore errors if for some reason there is no PK (unlikely)
                    handle.execute("ALTER TABLE manga_progress DROP PRIMARY KEY");
                } catch (Exception ignored) {
                    // PK might not exist or other obscure H2 state. Proceed to try adding the
                    // correct one.
                }

                try {
                    // 2. Add the correct Primary Key
                    handle.execute("ALTER TABLE manga_progress ADD PRIMARY KEY (manga_id, provider, profile_id)");
                    logger.info("✅ Verified/Updated manga_progress Primary Key schema.");
                } catch (Exception e) {
                    // This creates noise if the PK is already "implicitly" correct but we failed to
                    // drop it?
                    // But since we tried to drop it allowing exceptions, we should be clear.
                    // If this fails, it's a real issue.
                    logger.warn("Could not set Primary Key (possibly already correct): {}", e.getMessage());
                }
            } catch (Exception e) {
                logger.error("Database migration/verification failed", e);
            }

            handle.execute("CREATE INDEX IF NOT EXISTS idx_manga_lib_id ON manga_library(manga_id, provider)");
            handle.execute("CREATE INDEX IF NOT EXISTS idx_manga_ch_id ON manga_chapters(manga_id, provider)");

            logger.info("📚 Manga library schema initialized");
        });
    }

    /**
     * Add a manga to the library.
     */
    public void addToLibrary(MangaInfo manga) {
        jdbi.useHandle(handle -> {
            int updated = handle.createUpdate("""
                        UPDATE manga_library SET
                            title = ?, author = ?, cover_url = ?, added_date = ?
                        WHERE manga_id = ? AND provider = ?
                    """)
                    .bind(0, manga.title())
                    .bind(1, manga.author())
                    .bind(2, manga.coverUrl())
                    .bind(3, System.currentTimeMillis())
                    .bind(4, manga.id())
                    .bind(5, manga.provider())
                    .execute();

            if (updated == 0) {
                handle.createUpdate("""
                            INSERT INTO manga_library (manga_id, title, author, cover_url, provider, added_date)
                            VALUES (?, ?, ?, ?, ?, ?)
                        """)
                        .bind(0, manga.id())
                        .bind(1, manga.title())
                        .bind(2, manga.author())
                        .bind(3, manga.coverUrl())
                        .bind(4, manga.provider())
                        .bind(5, System.currentTimeMillis())
                        .execute();
            }
        });
        logger.info("📖 Added to library: {} ({})", manga.title(), manga.provider());
    }

    /**
     * Remove a manga from the library.
     */

    /**
     * Get a manga from the library.
     */
    public MangaInfo getManga(String mangaId, String provider) {
        return jdbi.withHandle(handle -> {
            return handle.createQuery("SELECT * FROM manga_library WHERE manga_id = ? AND provider = ?")
                    .bind(0, mangaId).bind(1, provider)
                    .map((rs, ctx) -> new MangaInfo(
                            rs.getString("manga_id"),
                            rs.getString("title"),
                            rs.getString("author"),
                            rs.getString("cover_url"),
                            null, // Description not stored
                            List.of(), // Tags not stored
                            rs.getString("provider")))
                    .findFirst().orElse(null);
        });
    }

    /**
     * Remove a specific chapter from the library.
     */
    public void removeChapter(String mangaId, String chapterId, String provider) {
        jdbi.useHandle(handle -> {
            handle.createUpdate("DELETE FROM manga_chapters WHERE manga_id = ? AND chapter_id = ? AND provider = ?")
                    .bind(0, mangaId).bind(1, chapterId).bind(2, provider)
                    .execute();
        });
    }

    /**
     * Remove a manga from the library.
     */
    public void removeFromLibrary(String mangaId, String provider) {
        jdbi.useHandle(handle -> {
            handle.createUpdate("DELETE FROM manga_library WHERE manga_id = ? AND provider = ?")
                    .bind(0, mangaId).bind(1, provider).execute();
            handle.createUpdate("DELETE FROM manga_chapters WHERE manga_id = ? AND provider = ?")
                    .bind(0, mangaId).bind(1, provider).execute();
            handle.execute("DELETE FROM manga_progress WHERE manga_id = ? AND provider = ?", mangaId, provider);
        });
    }

    /**
     * Check if a manga is in the library.
     */
    public boolean isInLibrary(String mangaId, String provider) {
        return jdbi.withHandle(handle -> {
            Integer count = handle.createQuery(
                    "SELECT COUNT(*) FROM manga_library WHERE manga_id = ? AND provider = ?")
                    .bind(0, mangaId).bind(1, provider)
                    .mapTo(Integer.class).one();
            return count != null && count > 0;
        });
    }

    /**
     * Get all library entries with chapter counts and reading progress.
     */
    public List<LibraryEntry> getLibrary() {
        return jdbi.withHandle(handle -> {
            return handle
                    .createQuery(
                            """
                                        SELECT l.manga_id, l.title, l.author, l.cover_url, l.provider, l.added_date,
                                               (SELECT COUNT(*) FROM manga_chapters c WHERE c.manga_id = l.manga_id AND c.provider = l.provider) as downloaded_chapters,
                                               NULL as last_chapter_num,
                                               (SELECT local_path FROM manga_chapters c WHERE c.manga_id = l.manga_id AND c.provider = l.provider ORDER BY c.chapter_num ASC LIMIT 1) as local_cover_path
                                        FROM manga_library l
                                        ORDER BY l.added_date DESC
                                    """)
                    .map((rs, ctx) -> {
                        return new LibraryEntry(
                                rs.getString("manga_id"),
                                rs.getString("title"),
                                rs.getString("author"),
                                rs.getString("cover_url"),
                                rs.getString("provider"),
                                rs.getLong("added_date"),
                                rs.getInt("downloaded_chapters"),
                                0,
                                null,
                                rs.getString("local_cover_path"));
                    })
                    .list();
        });
    }

    public List<LibraryEntry> getLibrary(String profileId) {
        return jdbi.withHandle(handle -> {
            String sql = """
                        SELECT l.manga_id, l.title, l.author, l.cover_url, l.provider, l.added_date,
                               (SELECT COUNT(*) FROM manga_chapters c WHERE c.manga_id = l.manga_id AND c.provider = l.provider) as downloaded_chapters,
                               p.last_chapter_num,
                               (SELECT local_path FROM manga_chapters c WHERE c.manga_id = l.manga_id AND c.provider = l.provider ORDER BY c.chapter_num ASC LIMIT 1) as local_cover_path
                        FROM manga_library l
                        LEFT JOIN manga_progress p ON l.manga_id = p.manga_id AND l.provider = p.provider AND p.profile_id = ?
                        ORDER BY l.added_date DESC
                    """;
            return handle.createQuery(sql)
                    .bind(0, profileId)
                    .map((rs, ctx) -> new LibraryEntry(
                            rs.getString("manga_id"),
                            rs.getString("title"),
                            rs.getString("author"),
                            rs.getString("cover_url"),
                            rs.getString("provider"),
                            rs.getLong("added_date"),
                            rs.getInt("downloaded_chapters"),
                            0,
                            rs.getString("last_chapter_num"),
                            rs.getString("local_cover_path")))
                    .list();
        });
    }

    /**
     * Mark a chapter as downloaded.
     */
    public void markChapterDownloaded(String mangaId, String chapterId, String chapterNum, String provider,
            String localPath) {
        jdbi.useHandle(handle -> {
            int updated = handle.createUpdate("""
                        UPDATE manga_chapters SET
                            chapter_num = ?, local_path = ?, downloaded_at = ?
                        WHERE manga_id = ? AND chapter_id = ? AND provider = ?
                    """)
                    .bind(0, chapterNum)
                    .bind(1, localPath)
                    .bind(2, System.currentTimeMillis())
                    .bind(3, mangaId)
                    .bind(4, chapterId)
                    .bind(5, provider)
                    .execute();

            if (updated == 0) {
                handle.createUpdate(
                        """
                                    INSERT INTO manga_chapters (manga_id, chapter_id, chapter_num, provider, local_path, downloaded_at)
                                    VALUES (?, ?, ?, ?, ?, ?)
                                """)
                        .bind(0, mangaId)
                        .bind(1, chapterId)
                        .bind(2, chapterNum)
                        .bind(3, provider)
                        .bind(4, localPath)
                        .bind(5, System.currentTimeMillis())
                        .execute();
            }
        });
    }

    /**
     * Get list of downloaded chapter IDs for a manga.
     */
    public List<String> getDownloadedChapterIds(String mangaId, String provider) {
        return jdbi.withHandle(handle -> {
            return handle.createQuery(
                    "SELECT chapter_id FROM manga_chapters WHERE manga_id = ? AND provider = ? ORDER BY chapter_num")
                    .bind(0, mangaId).bind(1, provider)
                    .mapTo(String.class).list();
        });
    }

    /**
     * Update reading progress.
     */
    public void updateProgress(String mangaId, String provider, String chapterId, String chapterNum, int page,
            String profileId) {
        jdbi.useHandle(handle -> {
            // Using UPDATE-then-INSERT pattern to avoid H2 MERGE locks
            int updated = handle.createUpdate("""
                        UPDATE manga_progress SET
                            last_chapter_id = ?, last_chapter_num = ?, last_page = ?, updated_at = ?
                        WHERE manga_id = ? AND provider = ? AND profile_id = ?
                    """)
                    .bind(0, chapterId)
                    .bind(1, chapterNum)
                    .bind(2, page)
                    .bind(3, System.currentTimeMillis())
                    .bind(4, mangaId)
                    .bind(5, provider)
                    .bind(6, profileId)
                    .execute();

            if (updated == 0) {
                try {
                    handle.createUpdate(
                            """
                                        INSERT INTO manga_progress (manga_id, provider, profile_id, last_chapter_id, last_chapter_num, last_page, updated_at)
                                        VALUES (?, ?, ?, ?, ?, ?, ?)
                                    """)
                            .bind(0, mangaId)
                            .bind(1, provider)
                            .bind(2, profileId)
                            .bind(3, chapterId)
                            .bind(4, chapterNum)
                            .bind(5, page)
                            .bind(6, System.currentTimeMillis())
                            .execute();
                } catch (Exception e) {
                    // Start condition race? Retry update?
                    // Just log for now. It's likely a duplicate key if inserted concurrently
                    logger.warn("Parallel insert for progress? " + e.getMessage());
                }
            }
        });
    }

    /**
     * Update the cover URL for a manga.
     */
    public void updateCover(String mangaId, String provider, String coverUrl) {
        jdbi.useHandle(handle -> {
            handle.createUpdate("UPDATE manga_library SET cover_url = ? WHERE manga_id = ? AND provider = ?")
                    .bind(0, coverUrl)
                    .bind(1, mangaId)
                    .bind(2, provider)
                    .execute();
        });
    }

    /**
     * Get reading progress for a manga.
     */
    public ReadingProgress getProgress(String mangaId, String provider, String profileId) {
        return jdbi.withHandle(handle -> {
            try {
                return handle.createQuery(
                        "SELECT * FROM manga_progress WHERE manga_id = ? AND provider = ? AND profile_id = ?")
                        .bind(0, mangaId).bind(1, provider).bind(2, profileId)
                        .map((rs, ctx) -> new ReadingProgress(
                                rs.getString("manga_id"),
                                rs.getString("last_chapter_id"),
                                rs.getString("last_chapter_num"),
                                rs.getInt("last_page"),
                                rs.getLong("updated_at")))
                        .findFirst().orElse(null);
            } catch (Exception e) {
                logger.error("Failed to get progress for {}/{}/{}", mangaId, provider, profileId, e);
                throw e;
            }
        });
    }

    // --- Profile Management ---

    public com.plugins.manga.internal.model.MangaProfile createProfile(String name) {
        String id = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO manga_profiles (id, name, created_at) VALUES (?, ?, ?)", id, name, now);
        });
        return new com.plugins.manga.internal.model.MangaProfile(id, name, now);
    }

    public List<com.plugins.manga.internal.model.MangaProfile> getProfiles() {
        return jdbi.withHandle(handle -> {
            return handle.createQuery("SELECT * FROM manga_profiles ORDER BY name ASC")
                    .map((rs, ctx) -> new com.plugins.manga.internal.model.MangaProfile(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getLong("created_at")))
                    .list();
        });
    }
}
