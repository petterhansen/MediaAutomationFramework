package com.framework.services.database;

import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-time migration utility to import legacy txt-based history into database.
 * Run this before deleting tools/history_tracking/ folder.
 */
public class HistoryMigration {
    private static final Logger logger = LoggerFactory.getLogger(HistoryMigration.class);

    public static void migrate(Kernel kernel) {
        File historyDir = new File(kernel.getToolsDir(), "history_tracking");

        if (!historyDir.exists() || !historyDir.isDirectory()) {
            logger.info("No history_tracking directory found. Skipping migration.");
            return;
        }

        File[] files = historyDir.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            logger.info("No history files to migrate.");
            return;
        }

        logger.info("🔄 Starting history migration from {} files...", files.length);

        AtomicInteger totalImported = new AtomicInteger(0);
        AtomicInteger totalSkipped = new AtomicInteger(0);

        for (File file : files) {
            String creatorName = file.getName().replace(".txt", "");
            logger.info("Migrating history for: {}", creatorName);

            AtomicInteger imported = new AtomicInteger(0);
            AtomicInteger skipped = new AtomicInteger(0);

            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                String line;

                while ((line = reader.readLine()) != null) {
                    String fileName = line.trim();
                    if (fileName.isEmpty())
                        continue;

                    // Check if already in database
                    boolean exists = kernel.getDatabaseService().getJdbi().withHandle(handle -> {
                        Integer count = handle.createQuery(
                                "SELECT COUNT(*) FROM media_items WHERE creator = ? AND file_name = ?")
                                .bind(0, creatorName)
                                .bind(1, fileName)
                                .mapTo(Integer.class)
                                .one();
                        return count > 0;
                    });

                    if (exists) {
                        skipped.incrementAndGet();
                        continue;
                    }

                    // Insert legacy record
                    kernel.getDatabaseService().getJdbi().useHandle(handle -> {
                        handle.createUpdate("""
                                    INSERT INTO media_items
                                    (creator, file_name, status, processed_at, url)
                                    VALUES (?, ?, 'LEGACY_MIGRATED', CURRENT_TIMESTAMP, NULL)
                                """)
                                .bind(0, creatorName)
                                .bind(1, fileName)
                                .execute();
                    });

                    imported.incrementAndGet();
                }

                logger.info("  ✅ {}: {} imported, {} skipped",
                        creatorName, imported.get(), skipped.get());
                totalImported.addAndGet(imported.get());
                totalSkipped.addAndGet(skipped.get());

            } catch (IOException e) {
                logger.error("Failed to migrate {}: {}", file.getName(), e.getMessage());
            }
        }

        logger.info("🎉 Migration complete! {} total records imported, {} skipped",
                totalImported.get(), totalSkipped.get());
        logger.info("💡 You can now safely delete: {}", historyDir.getAbsolutePath());
    }

    /**
     * Standalone migration tool - can be run from command line
     */
    public static void main(String[] args) {
        System.out.println("Starting History Migration...");
        Kernel kernel = Kernel.getInstance();
        migrate(kernel);
        System.out.println("Migration finished. Check logs for details.");
        System.exit(0);
    }
}
