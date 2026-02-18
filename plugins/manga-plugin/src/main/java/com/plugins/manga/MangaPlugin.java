package com.plugins.manga;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.config.Configuration;
import com.plugins.manga.internal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;

/**
 * MangaReader Plugin — adds manga reading and downloading capabilities
 * to the MediaAutomationFramework.
 *
 * Features:
 * - Search and browse manga from MangaDex and MangaPill
 * - Download chapters for offline reading
 * - Built-in web reader with vertical scroll and page-by-page modes
 * - Reading progress tracking
 * - Library management
 */
public class MangaPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(MangaPlugin.class);
    private MangaRouteRegistrar routeRegistrar;

    @Override
    public String getName() {
        return "MangaReader";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        logger.info("📚 MangaReader Plugin starting...");

        // 1. Setup default config
        setupDefaultSettings(kernel);

        // 2. Initialize services
        Configuration config = kernel.getConfigManager().getConfig();
        String cachePath = config.getPluginSetting(getName(), "cache_path", "media_cache");

        MangaApiService apiService = new MangaApiService(config);
        MangaChapterDownloader downloader = new MangaChapterDownloader(cachePath);
        MangaLibrary library = new MangaLibrary(kernel.getDatabaseService().getJdbi());
        MangaSource source = new MangaSource(kernel, apiService, downloader, library);

        // 3. Register services in the Kernel for cross-plugin access
        kernel.registerService(MangaApiService.class, apiService);
        kernel.registerService(MangaLibrary.class, library);

        // 4. Register routes on WebServer (Interface)
        com.framework.api.WebServer webServer = kernel.getService(com.framework.api.WebServer.class);
        if (webServer != null) {
            Path webRoot = findWebRoot();
            routeRegistrar = new MangaRouteRegistrar(
                    webServer, apiService, library, downloader, source, webRoot);
            routeRegistrar.registerRoutes();
            if (webRoot != null) {
                logger.info("🌐 Manga web reader available at /manga (web root: {})", webRoot);
            } else {
                logger.warn("⚠️ Manga web files not found — API routes registered but web pages will 404. " +
                        "Place web files at: web/manga/");
            }
        } else {
            logger.warn("⚠️ WebServer not available — manga routes not registered");
        }

        logger.info("✅ MangaReader plugin enabled (v{})", getVersion());
    }

    @Override
    public void onDisable() {
        logger.info("📚 MangaReader plugin disabled");
    }

    private void setupDefaultSettings(Kernel kernel) {
        Configuration config = kernel.getConfigManager().getConfig();
        String pluginName = getName();
        boolean dirty = false;

        if (config.getPluginSetting(pluginName, "cache_path", "").isEmpty()) {
            config.setPluginSetting(pluginName, "cache_path", "media_cache");
            dirty = true;
        }

        if (config.getPluginSetting(pluginName, "max_cache_size_gb", "").isEmpty()) {
            config.setPluginSetting(pluginName, "max_cache_size_gb", "10");
            dirty = true;
        }

        if (config.getPluginSetting(pluginName, "default_provider", "").isEmpty()) {
            config.setPluginSetting(pluginName, "default_provider", "mangadex");
            dirty = true;
        }

        if (config.getPluginSetting(pluginName, "allow_nsfw", "").isEmpty()) {
            config.setPluginSetting(pluginName, "allow_nsfw", "false");
            dirty = true;
        }

        if (dirty) {
            kernel.getConfigManager().saveConfig();
        }
    }

    private Path findWebRoot() {
        // Try several locations for the web files
        String[] searchPaths = {
                "web/manga",
                "plugins/manga-plugin/web/manga",
                "../plugins/manga-plugin/web/manga"
        };

        for (String path : searchPaths) {
            File dir = new File(path);
            if (dir.isDirectory() && new File(dir, "library.html").exists()) {
                return dir.toPath().toAbsolutePath();
            }
        }

        return null;
    }
}
