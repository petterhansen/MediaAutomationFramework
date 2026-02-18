package com.framework.services.stats;

import com.framework.core.Kernel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Portiert src/StatisticsManager.java
 * Verwaltet Statistiken über Creator und System-Metriken.
 */
public class StatisticsManager {
    private static final Logger logger = LoggerFactory.getLogger(StatisticsManager.class);
    private final File statsFile;
    private final File historyDir;
    private final Gson gson;
    private StatsData persistentData;
    private final Kernel kernel;

    // In-Memory Cache moved to DB-backed
    // private final Map<String, CreatorStats> cachedCreatorStats = new
    // ConcurrentHashMap<>();

    public record CreatorStats(int total, int images, int videos, int gifs, long lastActivity) {
    }

    private static class StatsData {
        long totalBytesDownloaded = 0;
        long totalBytesUploaded = 0; // Legacy / Untracked
        long totalFfmpegTimeSeconds = 0;
        long totalRequestsProcessed = 0;
        // Map<String, CreatorStats> creatorStats = new ConcurrentHashMap<>(); // Legacy
    }

    public StatisticsManager(Kernel kernel) {
        this.kernel = kernel;
        this.statsFile = new File(kernel.getToolsDir(), "stats.json");
        this.historyDir = new File(kernel.getToolsDir(), "history_tracking");
        if (!historyDir.exists())
            historyDir.mkdirs();

        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadPersistentData();
    }

    public synchronized void addDownload(long bytes) {
        persistentData.totalBytesDownloaded += bytes;
        save();
    }

    public synchronized void addUpload(long bytes) {
        persistentData.totalBytesUploaded += bytes;
        save();
    }

    public synchronized void addFfmpegTime(long seconds) {
        persistentData.totalFfmpegTimeSeconds += seconds;
        save();
    }

    public synchronized void addRequest() {
        persistentData.totalRequestsProcessed++;
        save();
    }

    // Legacy method - no-op now as DB tracks this via Pipeline
    public void updateCreatorStats(String creator, String filename) {
        // No-op: DatabaseService tracks this via markProcessed
    }

    // --- API Access Methods (DB-backed) ---

    public Map<String, CreatorStats> getAllCreatorsDetailed() {
        return convertStats(kernel.getDatabaseService().getCreatorStatistics());
    }

    public Map<String, CreatorStats> getTopCreatorsDetailed(int limit) {
        return getAllCreatorsDetailed().entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().total(), e1.getValue().total()))
                .limit(limit)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    private Map<String, CreatorStats> convertStats(
            Map<String, com.framework.services.database.DatabaseService.CreatorStatsDTO> dbStats) {
        Map<String, CreatorStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, com.framework.services.database.DatabaseService.CreatorStatsDTO> entry : dbStats
                .entrySet()) {
            com.framework.services.database.DatabaseService.CreatorStatsDTO dto = entry.getValue();
            // Note: lastActivity is not tracked in aggregated DTO yet, defaulting to 0 or
            // now?
            // We could add MAX(downloaded_at) to query if needed. For now 0.
            result.put(entry.getKey(),
                    new CreatorStats((int) dto.total, (int) dto.images, (int) dto.videos, 0, dto.lastActive));
        }
        return result;
    }

    // --- Persistence (Legacy / Requests only) ---

    private void loadPersistentData() {
        if (statsFile.exists()) {
            try (Reader r = new FileReader(statsFile, StandardCharsets.UTF_8)) {
                persistentData = gson.fromJson(r, StatsData.class);
            } catch (Exception e) {
                logger.error("Failed to load stats, creating new", e);
                persistentData = new StatsData();
            }
        } else {
            persistentData = new StatsData();
        }
    }

    private void save() {
        try (Writer w = new FileWriter(statsFile, StandardCharsets.UTF_8)) {
            gson.toJson(persistentData, w);
        } catch (IOException e) {
            logger.error("Save stats failed", e);
        }
    }

    // --- Delegate to DB or internal counter ---

    public long getTotalBytesDownloaded() {
        // return persistentData.totalBytesDownloaded; // Legacy
        return kernel.getDatabaseService().getTotalBytes();
    }

    public long getTotalBytesUploaded() {
        return persistentData.totalBytesUploaded; // Currently not tracked in DB per file, so keep legacy or remove?
        // Actually DB has 'uploaded_at', but we don't track upload bytes separately
        // usually same as file size.
        // For now, let's return DB total bytes for consistency or 0 if we don't track
        // it.
        // Let's stick to total bytes from DB as "Managed Bytes".
        // return kernel.getDatabaseService().getTotalBytes();
    }

    public long getTotalFfmpegTime() {
        // return persistentData.totalFfmpegTimeSeconds; // Legacy
        return (long) kernel.getDatabaseService().getTotalDuration();
    }

    public long getTotalRequests() {
        return persistentData.totalRequestsProcessed;
    }

    public int getTotalFiles() {
        return (int) kernel.getDatabaseService().getTotalDownloads();
    }
}