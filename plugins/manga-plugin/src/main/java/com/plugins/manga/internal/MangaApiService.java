package com.plugins.manga.internal;

import com.framework.core.config.Configuration;
import com.plugins.manga.internal.model.MangaDetails;
import com.plugins.manga.internal.model.MangaInfo;
import com.plugins.manga.internal.providers.MangaDexProvider;
import com.plugins.manga.internal.providers.MangaPillProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Aggregates multiple manga providers and dispatches requests.
 */
public class MangaApiService {
    private static final Logger logger = LoggerFactory.getLogger(MangaApiService.class);
    private final Map<String, MangaProvider> providers = new ConcurrentHashMap<>();
    private final Configuration config;

    public MangaApiService(Configuration config) {
        this.config = config;
        // Register built-in providers
        registerProvider(new MangaDexProvider());
        registerProvider(new MangaPillProvider());
        registerProvider(new com.plugins.manga.internal.providers.NHentaiProvider());

        logger.info("📚 MangaApiService initialized with {} providers: {}",
                providers.size(),
                providers.values().stream().map(MangaProvider::getDisplayName).collect(Collectors.joining(", ")));
    }

    public void registerProvider(MangaProvider provider) {
        providers.put(provider.getName(), provider);
    }

    /**
     * Search for manga. If provider is null/empty, searches all providers.
     * 
     * @param includeNsfw If true/false, overrides global setting. If null, uses
     *                    global setting.
     */
    public List<MangaInfo> search(String query, int limit, String providerName, Boolean includeNsfw) {
        boolean globalSetting = config.getPluginSetting("MangaReader", "allow_nsfw", "false").equalsIgnoreCase("true");
        boolean allowNsfw = (includeNsfw != null) ? includeNsfw : globalSetting;

        if (providerName != null && !providerName.isEmpty()) {
            MangaProvider provider = providers.get(providerName);
            if (provider == null) {
                logger.warn("Unknown provider: {}", providerName);
                return Collections.emptyList();
            }
            if (provider.isNsfw() && !allowNsfw) {
                logger.debug("🚫 Blocked NSFW search for provider: {}", providerName);
                return Collections.emptyList();
            }
            return provider.search(query, limit);
        }

        // Search all providers and merge results
        List<MangaInfo> results = new ArrayList<>();
        for (MangaProvider provider : providers.values()) {
            if (provider.isNsfw() && !allowNsfw)
                continue;

            try {
                List<MangaInfo> providerResults = provider.search(query, limit);
                results.addAll(providerResults);
            } catch (Exception e) {
                logger.warn("Search failed for provider {}: {}", provider.getName(), e.getMessage());
            }
        }
        return results;
    }

    /**
     * Get manga details from a specific provider.
     */
    public MangaDetails getDetails(String providerName, String mangaId) {
        MangaProvider provider = providers.get(providerName);
        if (provider == null) {
            logger.warn("Unknown provider: {}", providerName);
            return null;
        }
        return provider.getDetails(mangaId);
    }

    /**
     * Get chapter page URLs from a specific provider.
     */
    public List<String> getChapterPages(String providerName, String chapterId) {
        MangaProvider provider = providers.get(providerName);
        if (provider == null) {
            logger.warn("Unknown provider: {}", providerName);
            return Collections.emptyList();
        }
        return provider.getChapterPages(chapterId);
    }

    /**
     * Get list of available provider names.
     */
    public List<Map<String, Object>> getProviders() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MangaProvider provider : providers.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", provider.getName());
            info.put("displayName", provider.getDisplayName());
            info.put("isNsfw", provider.isNsfw());
            list.add(info);
        }
        return list;
    }
}
