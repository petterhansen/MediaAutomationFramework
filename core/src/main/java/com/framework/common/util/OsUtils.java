package com.framework.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Hilfsklasse für betriebssystemabhängige Operationen und Pfade.
 * Portierung von src/utils/OsUtils.java
 */
public class OsUtils {
    private static final Logger logger = LoggerFactory.getLogger(OsUtils.class);

    // Prüft einmalig beim Start, ob wir auf Windows sind
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /**
     * Gibt den korrekten Befehl für FFmpeg zurück.
     * Windows: ./tools/ffmpeg.exe
     * Linux: ffmpeg (globaler Systembefehl) oder ./tools/ffmpeg
     */
    public static String getFfmpegCommand() {
        if (IS_WINDOWS) {
            return new File("tools", "ffmpeg.exe").getAbsolutePath();
        } else {
            // Auf Linux prüfen wir zuerst, ob eine lokale Binary existiert
            File localFfmpeg = new File("tools", "ffmpeg");
            if (localFfmpeg.exists()) {
                makeExecutable(localFfmpeg);
                return localFfmpeg.getAbsolutePath();
            }
            // Fallback auf System-FFmpeg
            return "ffmpeg";
        }
    }

    /**
     * Gibt den korrekten Befehl für Aria2 zurück.
     * Windows: ./tools/aria2c.exe
     * Linux: aria2c (global installiert)
     */
    public static String getAria2Command() {
        if (IS_WINDOWS) {
            return new File("tools", "aria2c.exe").getAbsolutePath();
        } else {
            return "aria2c"; // Auf Linux global installiert erwartet
        }
    }

    /**
     * Gibt den Pfad zum Telegram Bot API Server zurück.
     * Windows: ./tools/telegram-bot-api.exe
     * Linux: ./tools/telegram-bot-api-linux (oder fallback auf telegram-bot-api)
     */
    public static String getTelegramServerExecutable() {
        if (IS_WINDOWS) {
            return new File("tools", "telegram-bot-api.exe").getAbsolutePath();
        } else {
            // Versuche spezifische Linux-Binary
            File linuxExe = new File("tools", "telegram-bot-api-linux");
            if (linuxExe.exists()) {
                makeExecutable(linuxExe);
                return linuxExe.getAbsolutePath();
            }

            // Versuche generische Binary
            File genericExe = new File("tools", "telegram-bot-api");
            if (genericExe.exists()) {
                makeExecutable(genericExe);
                return genericExe.getAbsolutePath();
            }

            return linuxExe.getAbsolutePath();
        }
    }

    /**
     * Setzt das Executable-Bit auf Linux/Unix Systemen.
     */
    public static void makeExecutable(File file) {
        if (IS_WINDOWS || !file.exists()) return;
        if (!file.canExecute()) {
            boolean success = file.setExecutable(true);
            if (!success) {
                logger.warn("Konnte chmod +x nicht auf {} anwenden.", file.getName());
            }
        }
    }

    public static boolean isWindows() {
        return IS_WINDOWS;
    }
}