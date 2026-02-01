package com.framework.services.stats;

import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HistoryManager {
    private static final Logger logger = LoggerFactory.getLogger(HistoryManager.class);
    private final File historyDir;

    // Optional: Einfacher Cache, um Festplattenzugriffe zu reduzieren
    private final Map<String, Set<String>> memoryCache = new ConcurrentHashMap<>();

    public HistoryManager(Kernel kernel) {
        // Nutzt den 'tools' Ordner des Kernels als Basis
        this.historyDir = new File(kernel.getToolsDir(), "history_tracking");
        if (!historyDir.exists()) {
            historyDir.mkdirs();
        }
    }

    public boolean isProcessed(String creatorName, String fileName) {
        if (creatorName == null || fileName == null) return false;

        // Check Memory Cache first (Performance!)
        if (memoryCache.containsKey(creatorName)) {
            return memoryCache.get(creatorName).contains(fileName);
        }

        // Fallback: Lade von Disk
        Set<String> history = loadHistory(creatorName);
        memoryCache.put(creatorName, history); // Cache füllen
        return history.contains(fileName);
    }

    public synchronized void markAsProcessed(String creatorName, String fileName) {
        if (creatorName == null || fileName == null) return;

        // 1. Update Cache
        if (!memoryCache.containsKey(creatorName)) {
            loadHistory(creatorName); // Initial laden wenn nicht da
        }
        memoryCache.get(creatorName).add(fileName);

        // 2. Append to File
        File historyFile = getHistoryFile(creatorName);
        try (PrintWriter out = new PrintWriter(new FileWriter(historyFile, true))) {
            out.println(fileName);
        } catch (IOException e) {
            logger.error("Fehler beim Schreiben der History für {}: {}", creatorName, e.getMessage());
        }
    }

    public boolean deleteHistory(String creatorName) {
        memoryCache.remove(creatorName);
        File f = getHistoryFile(creatorName);
        if (f.exists()) return f.delete();
        return false;
    }

    public int getHistorySize(String creatorName) {
        File f = getHistoryFile(creatorName);
        if (!f.exists()) return 0;
        // Zeilen zählen ist teuer, wir schätzen oder laden
        return loadHistory(creatorName).size();
    }

    public List<String> getLastEntries(String creatorName, int limit) {
        File f = getHistoryFile(creatorName);
        if (!f.exists()) return Collections.emptyList();
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - limit);
            return lines.subList(start, lines.size());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private Set<String> loadHistory(String creatorName) {
        File f = getHistoryFile(creatorName);
        Set<String> entries = Collections.synchronizedSet(new HashSet<>());

        if (!f.exists()) {
            memoryCache.put(creatorName, entries);
            return entries;
        }

        try (BufferedReader reader = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) entries.add(line.trim());
            }
        } catch (IOException e) {
            logger.error("Error loading history for {}", creatorName, e);
        }

        memoryCache.put(creatorName, entries);
        return entries;
    }

    private File getHistoryFile(String creatorName) {
        // Deine Logik für Dateinamen-Säuberung (beibehalten für Kompatibilität)
        String safeName = creatorName.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
        return new File(historyDir, safeName + ".txt");
    }
}