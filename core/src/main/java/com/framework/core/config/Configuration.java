package com.framework.core.config;

import java.util.HashMap;
import java.util.Map;

public class Configuration {
    // --- Haupteinstellungen ---
    public boolean debugMode = false;
    public String downloadPath = "downloads";
    public String tempDir = "temp";

    // --- Legacy / Core Settings ---
    public boolean telegramEnabled = false; // Kann später migriert werden
    public String telegramToken = "";
    public String telegramAdminId = "";
    public String telegramAllowedChats = "";

    // --- Plugin Steuerung (Aktivieren/Deaktivieren) ---
    // Key = Plugin Name, Value = Aktiviert (true/false)
    public Map<String, Boolean> plugins = new HashMap<>();

    // --- NEU: Plugin-Spezifische Einstellungen ---
    // Key = PluginName, Value = Map mit Settings (z.B. "ffmpegPath" -> "C:/...")
    public Map<String, Map<String, String>> pluginConfigs = new HashMap<>();

    public Configuration() {
        // Defaults
        plugins.put("CoreCommands", true);
        plugins.put("Downloader", true);
        plugins.put("TelegramIntegration", true); // Name muss exakt mit getName() übereinstimmen
        plugins.put("WebDashboard", true);
    }

    // Helper für Plugins um einfach an ihre Config zu kommen
    public String getPluginSetting(String pluginName, String key, String defaultValue) {
        if (!pluginConfigs.containsKey(pluginName))
            return defaultValue;
        return pluginConfigs.get(pluginName).getOrDefault(key, defaultValue);
    }

    public void setPluginSetting(String pluginName, String key, String value) {
        pluginConfigs.computeIfAbsent(pluginName, k -> new HashMap<>()).put(key, value);
    }
}