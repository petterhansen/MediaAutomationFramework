package com.framework.common.util;

import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Portierung von src/utils/BlacklistManager.java
 * Filtert Suchanfragen und Ergebnisse basierend auf einer Blacklist-Datei.
 */
public class BlacklistManager {
    private static final Logger logger = LoggerFactory.getLogger(BlacklistManager.class);
    private final Set<String> blockedTags = new HashSet<>();
    private final File blacklistFile;

    public BlacklistManager(Kernel kernel) {
        this.blacklistFile = new File(kernel.getToolsDir(), "blacklist.txt");
        load();
    }

    public void load() {
        blockedTags.clear();
        // Hardcoded Defaults für Sicherheit
        blockedTags.add("loli");
        blockedTags.add("shota");
        blockedTags.add("cub");

        if (blacklistFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(blacklistFile.toPath());
                for (String line : lines) {
                    String clean = line.trim().toLowerCase();
                    if (!clean.isEmpty() && !clean.startsWith("#")) {
                        blockedTags.add(clean);
                    }
                }
                logger.info("Blacklist geladen: {} Einträge", blockedTags.size());
            } catch (IOException e) {
                logger.error("Fehler beim Laden der Blacklist", e);
            }
        } else {
            logger.info("Keine blacklist.txt gefunden. Nutze Defaults.");
        }
    }

    public boolean isAllowed(String queryOrTags) {
        if (queryOrTags == null) return true;
        String lower = queryOrTags.toLowerCase();

        // Splitte Eingabe (z.B. "tag1 tag2 tag3")
        String[] parts = lower.split("[\\s,]+");

        for (String part : parts) {
            if (blockedTags.contains(part)) {
                logger.debug("Blacklist Hit: {}", part);
                return false;
            }
        }
        return true;
    }

    public Set<String> getBlacklist() {
        return Collections.unmodifiableSet(blockedTags);
    }
}