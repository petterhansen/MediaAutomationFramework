package com.framework.core.plugin;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.config.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class PluginLoader {
    private static final Logger logger = LoggerFactory.getLogger(PluginLoader.class);
    private final List<MediaPlugin> loadedPlugins = new ArrayList<>();
    private final Kernel kernel;

    public PluginLoader(Kernel kernel) {
        this.kernel = kernel;
    }

    public void loadPlugins() {
        File pluginDir = new File("plugins");
        if (!pluginDir.exists()) pluginDir.mkdirs();

        // Config holen
        Configuration config = kernel.getConfigManager().getConfig();

        File[] jars = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) return;

        try {
            URL[] urls = new URL[jars.length];
            for (int i = 0; i < jars.length; i++) {
                urls[i] = jars[i].toURI().toURL();
            }

            URLClassLoader ucl = new URLClassLoader(urls, this.getClass().getClassLoader());
            ServiceLoader<MediaPlugin> loader = ServiceLoader.load(MediaPlugin.class, ucl);

            for (MediaPlugin plugin : loader) {
                // PRÜFUNG: Ist Plugin in Config aktiv? (Default: true)
                boolean enabled = config.plugins.getOrDefault(plugin.getName(), true);

                if (enabled) {
                    try {
                        logger.info("Loading Plugin: {} v{}", plugin.getName(), plugin.getVersion());
                        plugin.onEnable(kernel);
                        loadedPlugins.add(plugin);
                    } catch (Exception e) {
                        logger.error("Failed to enable plugin: " + plugin.getName(), e);
                    }
                } else {
                    logger.info("Plugin {} is disabled in config.", plugin.getName());
                }
            }
        } catch (Exception e) {
            logger.error("Error loading plugins", e);
        }
    }

    public void disableAll() {
        for (MediaPlugin plugin : loadedPlugins) {
            try {
                plugin.onDisable();
            } catch (Exception e) {
                logger.error("Error disabling plugin: " + plugin.getName(), e);
            }
        }
        loadedPlugins.clear();
    }
}