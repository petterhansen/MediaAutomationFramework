package com.framework.core.plugin;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.config.Configuration;
import com.plugins.CoreCommandsPlugin; // <--- Importieren
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

    // Legacy list for compatibility
    private final List<MediaPlugin> loadedPlugins = new ArrayList<>();
    private final Kernel kernel;

    // Map to track loaded plugins and their ClassLoaders for unloading
    private final java.util.Map<String, MediaPlugin> activePlugins = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, URLClassLoader> pluginClassLoaders = new java.util.concurrent.ConcurrentHashMap<>();

    public PluginLoader(Kernel kernel) {
        this.kernel = kernel;
    }

    public void loadPlugins() {
        File pluginDir = new File("plugins");
        if (!pluginDir.exists())
            pluginDir.mkdirs();

        Configuration config = kernel.getConfigManager().getConfig();

        // 1. Internal Plugins
        loadInternalPlugin(new CoreCommandsPlugin(), config);

        // 2. External Plugins
        File[] jars = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null && jars.length > 0) {
            java.util.Arrays.sort(jars, java.util.Comparator.comparing(File::getName));

            for (File jar : jars) {
                try {
                    loadPluginFromFile(jar, config);
                } catch (Exception e) {
                    logger.error("Failed to load generic plugin: " + jar.getName(), e);
                }
            }
        }

        kernel.getConfigManager().saveConfig();
    }

    public boolean loadPluginFromFile(File jarFile, Configuration config) throws Exception {
        URL[] urls = new URL[] { jarFile.toURI().toURL() };
        URLClassLoader ucl = new URLClassLoader(urls, this.getClass().getClassLoader());

        ServiceLoader<MediaPlugin> loader = ServiceLoader.load(MediaPlugin.class, ucl);
        boolean anyLoaded = false;

        // Note: ServiceLoader might find multiple plugins in one JAR, but usually it's
        // one
        for (MediaPlugin plugin : loader) {
            // Check if already loaded
            if (activePlugins.containsKey(plugin.getName())) {
                logger.warn("Plugin {} is already loaded. Skipping duplicate.", plugin.getName());
                continue;
            }

            boolean success = loadPluginSafe(plugin, config);
            if (success) {
                // Track ClassLoader ONLY if successful
                pluginClassLoaders.put(plugin.getName(), ucl);
                anyLoaded = true;
            }
        }

        return anyLoaded;
    }

    public void unloadPlugin(String name) {
        if (!activePlugins.containsKey(name)) {
            logger.warn("Cannot unload unknown plugin: " + name);
            return;
        }

        MediaPlugin plugin = activePlugins.get(name);
        try {
            logger.info("🔌 Disabling plugin: {}", name);
            plugin.onDisable();
        } catch (Exception e) {
            logger.error("Error during onDisable for " + name, e);
        }

        activePlugins.remove(name);

        // Remove from list (legacy support)
        loadedPlugins.remove(plugin);

        // Close ClassLoader (Java 7+)
        URLClassLoader ucl = pluginClassLoaders.remove(name);
        if (ucl != null) {
            try {
                ucl.close();
            } catch (Exception e) {
                logger.warn("Failed to close ClassLoader for " + name, e);
            }
        }

        System.gc(); // Suggest GC to clean up classes
        logger.info("🗑️ Plugin {} unloaded.", name);
    }

    private boolean loadPluginSafe(MediaPlugin plugin, Configuration config) {
        String name = plugin.getName();

        if (!config.plugins.containsKey(name)) {
            logger.info("✨ New Plugin discovered: {}", name);
            config.plugins.put(name, true);
        }

        if (config.plugins.get(name)) {
            try {
                logger.info("Loading Plugin: {} v{}", name, plugin.getVersion());
                plugin.onEnable(kernel);
                loadedPlugins.add(plugin); // Keep legacy list for now
                activePlugins.put(name, plugin);
                return true;
            } catch (Exception e) {
                logger.error("Failed to enable plugin: " + name, e);
            }
        } else {
            logger.info("Plugin {} is disabled in config.", name);
        }
        return false;
    }

    private void loadInternalPlugin(MediaPlugin plugin, Configuration config) {
        loadPluginSafe(plugin, config);
    }

    public void disableAll() {
        // Create a copy of keys to avoid concurrent modification issues
        for (String name : new ArrayList<>(activePlugins.keySet())) {
            unloadPlugin(name);
        }
    }

    public java.util.Collection<MediaPlugin> getPlugins() {
        return activePlugins.values();
    }
}