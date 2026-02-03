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

        // ------------------------------------------------------------
        // 1. INTERNE PLUGINS LADEN (Die fest im Core sind)
        // ------------------------------------------------------------
        loadInternalPlugin(new CoreCommandsPlugin(), config);

        // ------------------------------------------------------------
        // 2. EXTERNE PLUGINS LADEN (Aus dem /plugins Ordner)
        // ------------------------------------------------------------
        File[] jars = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null && jars.length > 0) {
            try {
                URL[] urls = new URL[jars.length];
                for (int i = 0; i < jars.length; i++) {
                    urls[i] = jars[i].toURI().toURL();
                }

                // ClassLoader erstellen, der den aktuellen ClassLoader als Parent hat
                URLClassLoader ucl = new URLClassLoader(urls, this.getClass().getClassLoader());
                ServiceLoader<MediaPlugin> loader = ServiceLoader.load(MediaPlugin.class, ucl);

                for (MediaPlugin plugin : loader) {
                    loadPluginSafe(plugin, config);
                }
            } catch (Exception e) {
                logger.error("Error detecting external plugins", e);
            }
        }

        // Config speichern, falls neue Plugins (wie CoreCommands) automatisch registriert wurden
        kernel.getConfigManager().saveConfig();
    }

    /**
     * Lädt ein einzelnes Plugin sicher und prüft die Config.
     */
    private void loadPluginSafe(MediaPlugin plugin, Configuration config) {
        String name = plugin.getName();

        // Wenn Plugin noch nicht in Config steht -> Hinzufügen (Aktivieren)
        if (!config.plugins.containsKey(name)) {
            logger.info("✨ New Plugin discovered: {}", name);
            config.plugins.put(name, true);
        }

        // Prüfen ob aktiviert
        if (config.plugins.get(name)) {
            try {
                logger.info("Loading Plugin: {} v{}", name, plugin.getVersion());
                plugin.onEnable(kernel);
                loadedPlugins.add(plugin);
            } catch (Exception e) {
                logger.error("Failed to enable plugin: " + name, e);
            }
        } else {
            logger.info("Plugin {} is disabled in config.", name);
        }
    }

    /**
     * Hilfsmethode speziell für interne Plugins
     */
    private void loadInternalPlugin(MediaPlugin plugin, Configuration config) {
        // Interne Plugins sollten standardmäßig immer in der Config landen
        loadPluginSafe(plugin, config);
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