package com.framework.services.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portierung von src/analysis/ChatImporter.java
 * Analysiert Telegram JSON-Exporte und füllt die Statistik-Datenbank.
 */
public class ChatImporter {
    private static final Logger logger = LoggerFactory.getLogger(ChatImporter.class);
    private final StatisticsManager statsManager;
    private final Pattern tagPattern = Pattern.compile("#([a-zA-Z0-9_]+)");

    public ChatImporter(StatisticsManager statsManager) {
        this.statsManager = statsManager;
    }

    public void importJsonExport(File jsonFile) {
        if (!jsonFile.exists()) {
            logger.error("Import file not found: {}", jsonFile.getAbsolutePath());
            return;
        }

        new Thread(() -> {
            logger.info("📦 Importing JSON: {}", jsonFile.getName());
            try (FileReader reader = new FileReader(jsonFile, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("messages")) return;

                JsonArray messages = root.getAsJsonArray("messages");
                int count = 0;

                for (JsonElement el : messages) {
                    if (!el.isJsonObject()) continue;
                    JsonObject msg = el.getAsJsonObject();

                    String text = extractText(msg);
                    Matcher m = tagPattern.matcher(text);
                    if (m.find()) {
                        String creator = m.group(1);
                        // Typ-Erkennung sehr vereinfacht für Import
                        String type = "img";
                        if (msg.has("mime_type") && msg.get("mime_type").getAsString().startsWith("video")) {
                            type = "video.mp4";
                        }

                        statsManager.updateCreatorStats(creator, "import." + (type.equals("img") ? "jpg" : "mp4"));
                        count++;
                    }
                }
                logger.info("✅ Import finished. Processed {} tagged messages.", count);

            } catch (Exception e) {
                logger.error("Import failed", e);
            }
        }, "Importer-Thread").start();
    }

    private String extractText(JsonObject msg) {
        if (!msg.has("text")) return "";
        JsonElement t = msg.get("text");
        if (t.isJsonPrimitive()) return t.getAsString();
        if (t.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : t.getAsJsonArray()) {
                if (part.isJsonPrimitive()) sb.append(part.getAsString());
                else if (part.isJsonObject() && part.getAsJsonObject().has("text"))
                    sb.append(part.getAsJsonObject().get("text").getAsString());
            }
            return sb.toString();
        }
        return "";
    }
}