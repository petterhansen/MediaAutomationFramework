package com.framework.core.config;

import java.util.HashMap;
import java.util.Map;

public class Configuration {
    // --- Haupteinstellungen ---
    public boolean debugMode = false;
    public String downloadPath = "downloads";
    public String tempDir = "temp";

    // --- Telegram Bot ---
    public boolean telegramEnabled = false;
    public String telegramToken = "";
    public String telegramAdminId = "";

    // --- Plugin Steuerung (Aktivieren/Deaktivieren) ---
    // Key = Plugin Name, Value = Aktiviert (true/false)
    public Map<String, Boolean> plugins = new HashMap<>();

    public Configuration() {
        // Standardwerte setzen
        plugins.put("CoreCommands", true);
        plugins.put("Downloader", true);
        plugins.put("TelegramSink", false);
    }
}