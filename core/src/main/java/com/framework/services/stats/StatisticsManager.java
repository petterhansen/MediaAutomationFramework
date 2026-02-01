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

    // In-Memory Cache
    private final Map<String, CreatorStats> cachedCreatorStats = new ConcurrentHashMap<>();

    public record CreatorStats(int total, int images, int videos, int gifs, long lastActivity) {}

    private static class StatsData {
        long totalBytesDownloaded = 0;
        long totalBytesUploaded = 0;
        long totalFfmpegTimeSeconds = 0;
        long totalRequestsProcessed = 0;
        Map<String, CreatorStats> creatorStats = new ConcurrentHashMap<>();
    }

    public StatisticsManager(Kernel kernel) {
        this.statsFile = new File(kernel.getToolsDir(), "stats.json");
        this.historyDir = new File(kernel.getToolsDir(), "history_tracking");
        if (!historyDir.exists()) historyDir.mkdirs();

        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadPersistentData();
    }

    public synchronized void addDownload(long bytes) { persistentData.totalBytesDownloaded += bytes; save(); }
    public synchronized void addUpload(long bytes) { persistentData.totalBytesUploaded += bytes; save(); }
    public synchronized void addFfmpegTime(long seconds) { persistentData.totalFfmpegTimeSeconds += seconds; save(); }
    public synchronized void addRequest() { persistentData.totalRequestsProcessed++; save(); }

    public void updateCreatorStats(String creator, String filename) {
        if (creator == null || filename == null) return;
        String key = creator.trim().toLowerCase();

        // Typ-Erkennung aus src/StatisticsManager.java
        String type = "img";
        String l = filename.toLowerCase();
        if (l.endsWith(".gif")) type = "gif";
        else if (l.matches(".*\\.(mp4|m4v|mov|webm|mkv|avi)$")) type = "vid";

        String finalType = type;

        cachedCreatorStats.compute(key, (k, v) -> {
            int img = v == null ? 0 : v.images();
            int vid = v == null ? 0 : v.videos();
            int gif = v == null ? 0 : v.gifs();
            int tot = v == null ? 0 : v.total();

            if (finalType.equals("gif")) gif++;
            else if (finalType.equals("vid")) vid++;
            else img++;
            tot++;

            return new CreatorStats(tot, img, vid, gif, System.currentTimeMillis());
        });

        synchronized (this) {
            persistentData.creatorStats.put(key, cachedCreatorStats.get(key));
            save();
        }
    }

    // --- API Access Methods (NEU) ---

    public Map<String, CreatorStats> getAllCreatorsDetailed() {
        return cachedCreatorStats.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().total(), e1.getValue().total()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    public Map<String, CreatorStats> getTopCreatorsDetailed(int limit) {
        return cachedCreatorStats.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().total(), e1.getValue().total()))
                .limit(limit)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    // --- Persistence ---

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

        if (persistentData.creatorStats == null) {
            persistentData.creatorStats = new ConcurrentHashMap<>();
        }

        // Auto-Repair & Migration
        if (persistentData.creatorStats.isEmpty()) {
            importFromHistoryFiles();
        }

        cachedCreatorStats.putAll(persistentData.creatorStats);
    }

    private void importFromHistoryFiles() {
        File[] files = historyDir.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null) return;
        logger.info("Migrating stats from {} history files...", files.length);

        for (File f : files) {
            String creator = f.getName().replace(".txt", "").toLowerCase();
            int img = 0, vid = 0, gif = 0, tot = 0;
            long lastMod = f.lastModified();

            try (BufferedReader reader = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String l = line.trim().toLowerCase();
                    if (l.isEmpty()) continue;
                    tot++;
                    if (l.endsWith(".gif")) gif++;
                    else if (l.matches(".*\\.(mp4|m4v|mov|webm|mkv|avi)$")) vid++;
                    else img++;
                }
            } catch (IOException e) {
                logger.warn("Error reading history file: {}", f.getName());
            }

            if (tot > 0) {
                CreatorStats s = new CreatorStats(tot, img, vid, gif, lastMod);
                persistentData.creatorStats.put(creator, s);
            }
        }
        save();
    }

    private void save() {
        try (Writer w = new FileWriter(statsFile, StandardCharsets.UTF_8)) {
            gson.toJson(persistentData, w);
        } catch (IOException e) { logger.error("Save stats failed", e); }
    }

    public long getTotalBytesDownloaded() { return persistentData.totalBytesDownloaded; }
    public long getTotalBytesUploaded() { return persistentData.totalBytesUploaded; }
    public long getTotalFfmpegTime() { return persistentData.totalFfmpegTimeSeconds; }
    public long getTotalRequests() { return persistentData.totalRequestsProcessed; }

    // Berechnung der Gesamtdateien basierend auf den Creatorn
    public int getTotalFiles() {
        return persistentData.creatorStats.values().stream().mapToInt(CreatorStats::total).sum();
    }
}