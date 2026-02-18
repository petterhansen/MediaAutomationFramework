package com.plugins.manga.internal;

import com.google.gson.Gson;
import com.plugins.manga.internal.model.*;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Registers all manga-related routes on the DashboardServer.
 * Handles both the web UI pages and REST API endpoints.
 */
public class MangaRouteRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(MangaRouteRegistrar.class);
    private final Gson gson = new Gson();
    private final com.framework.api.WebServer webServer;
    private final MangaApiService apiService;
    private final MangaLibrary library;
    private final MangaChapterDownloader downloader;
    private final MangaSource source;
    private final Path webRoot;

    public MangaRouteRegistrar(com.framework.api.WebServer webServer, MangaApiService apiService,
            MangaLibrary library, MangaChapterDownloader downloader,
            MangaSource source, Path webRoot) {
        this.webServer = webServer;
        this.apiService = apiService;
        this.library = library;
        this.downloader = downloader;
        this.source = source;
        this.webRoot = webRoot;
    }

    /**
     * Register all manga routes on the WebServer.
     * API routes are always registered. Web page routes only register if webRoot is
     * available.
     */
    public void registerRoutes() {
        // --- Public API routes (manga is now public) ---
        webServer.registerRoute("/api/manga/search", this::handleSearch, false);
        webServer.registerRoute("/api/manga/details", this::handleDetails, false);
        webServer.registerRoute("/api/manga/download", this::handleDownload, false);
        webServer.registerRoute("/api/manga/library", this::handleLibrary, false);
        webServer.registerRoute("/api/manga/pages", this::handlePages, false);
        webServer.registerRoute("/api/manga/chapter", this::handleChapter, false);
        webServer.registerRoute("/api/manga/progress", this::handleProgress, false);
        webServer.registerRoute("/api/manga/profiles", this::handleProfiles, false);
        webServer.registerRoute("/api/manga/providers", this::handleProviders, false);
        webServer.registerRoute("/api/manga/cover", this::handleCover, false);
        webServer.registerRoute("/manga/page", this::handlePageImage, false);

        // --- Public routes (HTML pages + assets) — only if web files exist ---
        if (webRoot != null) {
            webServer.registerRoute("/manga", this::handleLibraryPage, false);
            webServer.registerRoute("/manga/reader", this::handleReaderPage, false);
            // Login page removed in favor of profile system
            // webServer.registerRoute("/manga/login", this::handleLoginPage, false);
            webServer.registerRoute("/manga/css/", this::handleStaticAsset, false);
            webServer.registerRoute("/manga/js/", this::handleStaticAsset, false);
            webServer.registerRoute("/manga/favicon.ico", this::handleStaticAsset, false);
            logger.info("📚 Manga web routes registered (web root: {})", webRoot);
        }

        logger.info("📚 Manga API routes registered on WebServer");
    }

    // ======================== PAGE HANDLERS ========================

    private void handleLibraryPage(HttpExchange exchange) throws IOException {
        serveHtmlFile(exchange, "library.html");
    }

    private void handleLoginPage(HttpExchange exchange) throws IOException {
        serveHtmlFile(exchange, "login.html");
    }

    private void handleReaderPage(HttpExchange exchange) throws IOException {
        serveHtmlFile(exchange, "reader.html");
    }

    private void handleStaticAsset(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // Remove /manga/ prefix to get relative path
        String relativePath = path.substring("/manga/".length());
        Path filePath = webRoot.resolve(relativePath);

        if (!Files.exists(filePath) || !filePath.startsWith(webRoot)) {
            sendError(exchange, 404, "Not found");
            return;
        }

        String contentType = guessContentType(filePath.toString());
        byte[] data = Files.readAllBytes(filePath);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    // ======================== API HANDLERS ========================

    private void handleSearch(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        Map<String, String> params = parseQueryParams(exchange);
        String query = params.get("q");
        String provider = params.getOrDefault("provider", "");
        int limit = parseIntParam(params, "limit", 20);

        if (query == null || query.isBlank()) {
            sendError(exchange, 400, "Missing query parameter 'q'");
            return;
        }

        Boolean includeNsfw = null;
        if (params.containsKey("nsfw")) {
            includeNsfw = Boolean.parseBoolean(params.get("nsfw"));
        }

        List<MangaInfo> results = apiService.search(query, limit, provider.isEmpty() ? null : provider, includeNsfw);
        sendJson(exchange, results);
    }

    private void handleDetails(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            Map<String, String> params = parseQueryParams(exchange);
            String id = params.get("id");
            String provider = params.get("provider");

            if (id == null || provider == null) {
                sendError(exchange, 400, "Missing 'id' or 'provider' parameter");
                return;
            }

            String profileId = params.get("profileId"); // Optional

            MangaDetails details = apiService.getDetails(provider, id);
            if (details == null) {
                sendError(exchange, 404, "Manga not found");
                return;
            }

            // Add library status and downloaded chapters
            boolean inLibrary = library.isInLibrary(id, provider);
            List<String> downloadedChapters = library.getDownloadedChapterIds(id, provider);
            ReadingProgress progress = null;

            if (profileId != null && !profileId.isBlank()) {
                progress = library.getProgress(id, provider, profileId);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("details", details);
            response.put("inLibrary", inLibrary);
            response.put("downloadedChapters", downloadedChapters);
            response.put("progress", progress);

            sendJson(exchange, response);
        } catch (Exception e) {
            logger.error("Failed to handle details request", e);
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request = gson.fromJson(body, Map.class);

        if (request == null || !request.containsKey("mangaId") || !request.containsKey("provider")) {
            sendError(exchange, 400, "Missing mangaId or provider");
            return;
        }

        // Run download in background thread
        new Thread(() -> source.execute(request), "manga-download").start();

        sendJson(exchange, Map.of("status", "ok", "message", "Download started"));
    }

    private void handleLibrary(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            try {
                Map<String, String> params = parseQueryParams(exchange);
                String profileId = params.get("profileId");

                // If profileId is present, use it to get profile-specific progress.
                // Otherwise, call the parameterless version (which might return duplicates if
                // multiple profiles exist,
                // but usually the frontend sends a profileId).
                List<LibraryEntry> entries;
                if (profileId != null && !profileId.isBlank()) {
                    entries = library.getLibrary(profileId);
                } else {
                    entries = library.getLibrary();
                }

                // Post-process entries to inject local cover URLs if available
                List<LibraryEntry> processedEntries = new ArrayList<>();
                for (LibraryEntry entry : entries) {
                    String coverUrl = entry.coverUrl();

                    // If we have a local chapter path, try to use its first page as cover
                    if (entry.localCoverPath() != null) {
                        try {
                            File chapterDir = new File(entry.localCoverPath());
                            if (chapterDir.exists() && chapterDir.isDirectory()) {
                                File[] pages = downloader.getLocalPages(chapterDir);
                                if (pages != null && pages.length > 0) {
                                    // Use the first page as cover
                                    coverUrl = "/manga/page?path=" + java.net.URLEncoder.encode(
                                            pages[0].getAbsolutePath(), StandardCharsets.UTF_8);
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to resolve local cover for {}", entry.title(), e);
                        }
                    }

                    processedEntries.add(new LibraryEntry(
                            entry.mangaId(), entry.title(), entry.author(), coverUrl,
                            entry.provider(), entry.addedDate(), entry.downloadedChapters(),
                            entry.totalChapters(), entry.lastReadChapter(), entry.localCoverPath()));
                }

                sendJson(exchange, processedEntries);
            } catch (Exception e) {
                logger.error("Failed to fetch library", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        } else if ("POST".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> request = gson.fromJson(body, Map.class);

            if (request == null || !request.containsKey("id") || !request.containsKey("provider")) {
                sendError(exchange, 400, "Missing manga data");
                return;
            }

            MangaInfo info = new MangaInfo(
                    request.get("id"),
                    request.getOrDefault("title", "Unknown"),
                    request.getOrDefault("author", ""),
                    request.getOrDefault("coverUrl", null),
                    request.getOrDefault("description", ""),
                    List.of(),
                    request.get("provider"));
            library.addToLibrary(info);
            sendJson(exchange, Map.of("status", "ok"));
        } else if ("DELETE".equals(exchange.getRequestMethod())) {
            Map<String, String> params = parseQueryParams(exchange);
            String mangaId = params.get("id");
            String provider = params.get("provider");

            if (mangaId == null || provider == null) {
                sendError(exchange, 400, "Missing id or provider");
                return;
            }

            // Get manga info to find directory name (title)
            MangaInfo info = library.getManga(mangaId, provider);
            if (info != null) {
                downloader.deleteManga(info.title());
            }

            library.removeFromLibrary(mangaId, provider);
            sendJson(exchange, Map.of("status", "ok"));
        } else {
            sendError(exchange, 405, "Method not allowed");
        }
    }

    private void handleChapter(HttpExchange exchange) throws IOException {
        if ("DELETE".equals(exchange.getRequestMethod())) {
            Map<String, String> params = parseQueryParams(exchange);
            String mangaId = params.get("mangaId");
            String chapterId = params.get("chapterId");
            String provider = params.get("provider");
            // We need chapterNum for file deletion, passed from frontend
            String chapterNum = params.get("chapterNum");

            if (mangaId == null || chapterId == null || provider == null) {
                sendError(exchange, 400, "Missing parameters");
                return;
            }

            // Get manga info for directory name
            MangaInfo info = library.getManga(mangaId, provider);
            if (info != null && chapterNum != null) {
                downloader.deleteChapter(info.title(), chapterNum);
            }

            library.removeChapter(mangaId, chapterId, provider);
            sendJson(exchange, Map.of("status", "ok"));
        } else {
            sendError(exchange, 405, "Method not allowed");
        }
    }

    private void handlePages(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        Map<String, String> params = parseQueryParams(exchange);
        String chapterId = params.get("chapterId");
        String provider = params.get("provider");
        String mangaSlug = params.getOrDefault("mangaSlug", "");

        if (chapterId == null || provider == null) {
            sendError(exchange, 400, "Missing chapterId or provider");
            return;
        }

        // Check if locally cached first
        if (!mangaSlug.isEmpty()) {
            String chapterNum = params.getOrDefault("chapterNum", "");
            if (downloader.isChapterCached(mangaSlug, chapterNum)) {
                File chapterDir = downloader.getChapterCachePath(mangaSlug, chapterNum);
                File[] localPages = downloader.getLocalPages(chapterDir);
                List<String> localUrls = new ArrayList<>();
                for (File page : localPages) {
                    localUrls.add("/manga/page?path=" + java.net.URLEncoder.encode(
                            page.getAbsolutePath(), StandardCharsets.UTF_8));
                }
                sendJson(exchange, Map.of("pages", localUrls, "cached", true));
                return;
            }
        }

        // Fetch from provider
        List<String> pages = apiService.getChapterPages(provider, chapterId);
        sendJson(exchange, Map.of("pages", pages, "cached", false));
    }

    private void handleProgress(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> params = parseQueryParams(exchange);
                String mangaId = params.get("mangaId");
                String provider = params.getOrDefault("provider", "mangadex");

                String profileId = params.get("profileId");

                if (mangaId == null) {
                    sendError(exchange, 400, "Missing mangaId");
                    return;
                }

                if (profileId == null) {
                    sendJson(exchange, Map.of()); // No profile, no progress
                    return;
                }

                ReadingProgress progress = library.getProgress(mangaId, provider, profileId);
                sendJson(exchange, progress != null ? progress : Map.of());
            } else if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> request = gson.fromJson(body, Map.class);

                String mangaId = request.get("mangaId");
                String provider = request.getOrDefault("provider", "mangadex");
                String chapterId = request.get("chapterId");
                String chapterNum = request.getOrDefault("chapterNum", "0");
                int page = Integer.parseInt(request.getOrDefault("page", "0"));
                String profileId = request.get("profileId");

                if (profileId != null && !profileId.isBlank()) {
                    library.updateProgress(mangaId, provider, chapterId, chapterNum, page, profileId);
                }
                sendJson(exchange, Map.of("status", "ok"));
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        } catch (Exception e) {
            logger.error("Failed to handle progress request", e);
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void handleCover(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> request = gson.fromJson(body, Map.class);

        String mangaId = request.get("mangaId");
        String provider = request.get("provider");
        String coverUrl = request.get("coverUrl");

        if (mangaId == null || provider == null) {
            sendError(exchange, 400, "Missing mangaId or provider");
            return;
        }

        // Auto-fix if coverUrl is missing
        if (coverUrl == null || coverUrl.isBlank()) {
            try {
                // 1. Get details to find first chapter
                MangaDetails details = apiService.getDetails(provider, mangaId);
                if (details != null && details.chapters() != null && !details.chapters().isEmpty()) {
                    // 2. Get first chapter (chapters are sorted desc usually, but let's check
                    // provider logic)
                    // MangaDexProvider sorts asc? No, usually lists are desc.
                    // MangaPillProvider sorts desc (collections.reverse called).
                    // We want the FIRST chapter (Chapter 1). The list might be ordered differently.
                    // Let's just try the LAST element if it's potentially Chapter 1?
                    // Actually, most logical cover is Volume 1 Chapter 1.
                    // If the list is 100, 99... 1, then we want the last one.
                    // If the list is 1, 2... 100, we want the first one.
                    // Let's use logic: find chapter with lowest number? Or just first in list?
                    // Use first chapter in the list provided by getDetails.
                    // Providers usually return them in a logical reading order or reverse.
                    ChapterInfo firstChapter = details.chapters().get(details.chapters().size() - 1); // safe bet for
                                                                                                      // "start" is
                                                                                                      // usually end of
                                                                                                      // list if desc

                    // 3. Fetch pages
                    List<String> pages = apiService.getChapterPages(provider, firstChapter.id());
                    if (pages != null && !pages.isEmpty()) {
                        coverUrl = pages.get(0);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to auto-fetch cover for {} ({})", mangaId, provider, e);
            }
        }

        if (coverUrl != null && !coverUrl.isBlank()) {
            library.updateCover(mangaId, provider, coverUrl);
            sendJson(exchange, Map.of("status", "ok", "coverUrl", coverUrl));
        } else {
            sendError(exchange, 404, "Could not find a cover");
        }
    }

    private void handleProviders(HttpExchange exchange) throws IOException {
        sendJson(exchange, apiService.getProviders());
    }

    private void handleProfiles(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, library.getProfiles());
        } else if ("POST".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> request = gson.fromJson(body, Map.class);
            String name = request.get("name");
            if (name == null || name.isBlank()) {
                sendError(exchange, 400, "Name is required");
                return;
            }
            MangaProfile profile = library.createProfile(name);
            sendJson(exchange, profile);
        } else {
            sendError(exchange, 405, "Method not allowed");
        }
    }

    private void handlePageImage(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQueryParams(exchange);
        String path = params.get("path");

        if (path == null || path.isEmpty()) {
            sendError(exchange, 400, "Missing path parameter");
            return;
        }

        File file = new File(path);

        // Security: Ensure the file is actually inside the manga cache directory
        // We resolve the cache dir relative to the execution directory
        File mangaCacheDir = new File("media_cache/manga").getCanonicalFile();
        String canonicalFile = file.getCanonicalPath();

        // Check if file exists AND is within the manga cache
        if (!file.exists() || !file.isFile()) {
            logger.warn("❌ Image not found: {}", path);
            sendError(exchange, 404, "Image not found");
            return;
        }

        if (!canonicalFile.startsWith(mangaCacheDir.getPath())) {
            logger.warn("❌ Access denied for image path: {} (Not in {})", canonicalFile, mangaCacheDir.getPath());
            sendError(exchange, 403, "Access denied");
            return;
        }

        String contentType = guessContentType(file.getName());
        byte[] data = Files.readAllBytes(file.toPath());

        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Cache for 1 day
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    // ======================== UTILITY METHODS ========================

    private void serveHtmlFile(HttpExchange exchange, String fileName) throws IOException {
        Path filePath = webRoot.resolve(fileName);
        if (!Files.exists(filePath)) {
            sendError(exchange, 404, "Page not found: " + fileName);
            return;
        }
        byte[] data = Files.readAllBytes(filePath);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void sendJson(HttpExchange exchange, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        String json = gson.toJson(Map.of("error", message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> parseQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty())
            return params;

        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private int parseIntParam(Map<String, String> params, String key, int defaultValue) {
        String val = params.get(key);
        if (val == null)
            return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String guessContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html"))
            return "text/html; charset=utf-8";
        if (lower.endsWith(".css"))
            return "text/css; charset=utf-8";
        if (lower.endsWith(".js"))
            return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json"))
            return "application/json; charset=utf-8";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
            return "image/jpeg";
        if (lower.endsWith(".png"))
            return "image/png";
        if (lower.endsWith(".webp"))
            return "image/webp";
        if (lower.endsWith(".gif"))
            return "image/gif";
        if (lower.endsWith(".svg"))
            return "image/svg+xml";
        if (lower.endsWith(".ico"))
            return "image/x-icon";
        return "application/octet-stream";
    }
}
