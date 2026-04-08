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
    
    // In-memory cache for chapter pages to avoid redundant scraping
    // Key: provider:chapterId
    private final Map<String, List<String>> pageCache = new ConcurrentHashMap<>();
    private final Map<String, Long> pageCacheExpiration = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 60 * 60 * 1000; // 1 hour

    public MangaApiService(Configuration config) {
        this.config = config;
        // Register built-in providers
        registerProvider(new MangaDexProvider());
        registerProvider(new MangaPillProvider());

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
     * Get popular manga. If providerName is null/empty, fetches from all providers.
     */
    public List<MangaInfo> getPopular(int limit, String providerName) {
        if (providerName != null && !providerName.isEmpty()) {
            MangaProvider provider = providers.get(providerName);
            return (provider != null) ? provider.getPopular(limit) : Collections.emptyList();
        }

        List<MangaInfo> results = new ArrayList<>();
        for (MangaProvider provider : providers.values()) {
            if (provider.getName().equalsIgnoreCase("mangadex")) continue;
            try {
                results.addAll(provider.getPopular(limit));
            } catch (Exception e) {
                logger.warn("Popular fetch failed for provider {}: {}", provider.getName(), e.getMessage());
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
        String cacheKey = providerName + ":" + chapterId;
        
        // Return from cache if still valid
        if (pageCache.containsKey(cacheKey)) {
            Long expiration = pageCacheExpiration.get(cacheKey);
            if (expiration != null && System.currentTimeMillis() < expiration) {
                logger.debug("⚡ Cache hit for chapter pages: {}", cacheKey);
                return pageCache.get(cacheKey);
            } else {
                // Expired
                pageCache.remove(cacheKey);
                pageCacheExpiration.remove(cacheKey);
            }
        }

        MangaProvider provider = providers.get(providerName);
        if (provider == null) {
            logger.warn("Unknown provider: {}", providerName);
            return Collections.emptyList();
        }
        
        logger.debug("🌐 Fetching chapter pages from provider: {} -> {}", providerName, chapterId);
        List<String> pages = provider.getChapterPages(chapterId);
        
        // Store in cache
        if (pages != null && !pages.isEmpty()) {
            pageCache.put(cacheKey, pages);
            pageCacheExpiration.put(cacheKey, System.currentTimeMillis() + CACHE_DURATION_MS);
        }
        
        return pages;
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

    /**
     * Get a random manga from a specific provider. Defaults to MangaPill.
     */
    public MangaInfo getRandom(String providerName) {
        List<MangaInfo> results = getRandom(providerName, 1);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Get multiple random manga from a specific provider.
     */
    public List<MangaInfo> getRandom(String providerName, int limit) {
        if (providerName == null || providerName.isEmpty()) {
            providerName = "mangapill";
        }
        MangaProvider provider = providers.get(providerName);
        return (provider != null) ? provider.getRandom(limit) : Collections.emptyList();
    }

    public List<String> getGenres() {
        Set<String> allGenres = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MangaProvider provider : providers.values()) {
            try {
                allGenres.addAll(provider.getGenres());
            } catch (Exception e) {
                logger.warn("Failed to get genres from provider: {}", provider.getName());
            }
        }
        return new ArrayList<>(allGenres);
    }

    /**
     * Get manga by genre with pagination and sorting.
     */
    public List<MangaInfo> getByGenre(String genre, int limit, int page, String sort, String providerFilter) {
        if (providerFilter != null && !providerFilter.isBlank()) {
            MangaProvider provider = providers.get(providerFilter.toLowerCase());
            if (provider != null) {
                return provider.getByGenre(genre, limit, page, sort);
            }
            return Collections.emptyList();
        }

        List<MangaInfo> allResults = new ArrayList<>();
        // Simple strategy: ask all providers and combine
        int perProviderLimit = Math.max(5, limit); 
        for (MangaProvider provider : providers.values()) {
            try {
                List<MangaInfo> providerResults = provider.getByGenre(genre, perProviderLimit, page, sort);
                allResults.addAll(providerResults);
            } catch (Exception e) {
                logger.warn("Failed to get manga by genre from provider {}: {}", provider.getName(), e.getMessage());
            }
        }
        
        // If sorting globally (only "name" makes sense across providers)
        if ("name".equalsIgnoreCase(sort)) {
            allResults.sort((a, b) -> {
                String t1 = a.title() != null ? a.title() : "";
                String t2 = b.title() != null ? b.title() : "";
                return t1.compareToIgnoreCase(t2);
            });
        }

        if (allResults.size() > limit) {
            return allResults.subList(0, limit);
        }
        return allResults;
    }
}
