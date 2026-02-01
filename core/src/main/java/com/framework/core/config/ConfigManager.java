package com.framework.core.config;

import com.framework.core.Kernel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);

    private final File configFile;
    private final Gson gson;
    private Configuration configuration;

    public ConfigManager(Kernel kernel) {
        // Liegt zentral im tools-Ordner
        this.configFile = new File(kernel.getToolsDir(), "config.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    public Configuration getConfig() {
        return configuration;
    }

    public synchronized void save() {
        try (Writer w = new FileWriter(configFile, StandardCharsets.UTF_8)) {
            gson.toJson(configuration, w);
        } catch (IOException e) {
            logger.error("Failed to save configuration", e);
        }
    }

    private void load() {
        if (!configFile.exists()) {
            configuration = new Configuration();
            logger.info("No config file found. Created default configuration.");
            save(); // Defaults schreiben
            return;
        }

        try (Reader r = new FileReader(configFile, StandardCharsets.UTF_8)) {
            configuration = gson.fromJson(r, Configuration.class);
            if (configuration == null) configuration = new Configuration();
            logger.info("Configuration loaded.");
        } catch (Exception e) {
            logger.error("Failed to load configuration, using defaults", e);
            configuration = new Configuration();
        }
    }

    public void saveConfig() {
        try (Writer writer = Files.newBufferedWriter(configFile.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(this.configuration, writer);
            logger.info("Configuration saved to disk.");
        } catch (IOException e) {
            logger.error("Failed to save config", e);
        }
    }

    public void updateConfig(Configuration newConfig) {
        this.configuration = newConfig;
        saveConfig();
    }
}