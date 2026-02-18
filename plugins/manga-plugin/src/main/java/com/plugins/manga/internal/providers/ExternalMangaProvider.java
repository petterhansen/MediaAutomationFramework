package com.plugins.manga.internal.providers;

import com.google.gson.*;
import com.plugins.manga.internal.MangaProvider;
import com.plugins.manga.internal.model.ChapterInfo;
import com.plugins.manga.internal.model.MangaDetails;
import com.plugins.manga.internal.model.MangaInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A config-driven MangaProvider that reads its extraction rules from an .msrc
 * file.
 * Supports both HTML (CSS selectors via Jsoup) and JSON (JsonPath-like via
 * Gson) source types.
 */
public class ExternalMangaProvider implements MangaProvider {
    private static final Logger logger = LoggerFactory.getLogger(ExternalMangaProvider.class);
    private static final Gson gson = new Gson();

    private final JsonObject config;
    private final String name;
    private final String displayName;
    private final String type; // "html" or "json"
    private final String baseUrl;
    private final String userAgent;
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();

    public ExternalMangaProvider(JsonObject config) {
        this.config = config;
        this.name = config.get("name").getAsString();
        this.displayName = config.get("displayName").getAsString();
        this.type = config.has("type") ? config.get("type").getAsString() : "html";
        this.baseUrl = config.get("baseUrl").getAsString().replaceAll("/$", "");
        this.userAgent = config.has("userAgent")
                ? config.get("userAgent").getAsString()
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

        if (config.has("headers")) {
            JsonObject headers = config.getAsJsonObject("headers");
            for (Map.Entry<String, JsonElement> entry : headers.entrySet()) {
                defaultHeaders.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    // ─── SEARCH ───────────────────────────────────────────────────────────

    @Override
    public List<MangaInfo> search(String query, int limit) {
        JsonObject searchCfg = config.getAsJsonObject("search");
        if (searchCfg == null) {
            logger.warn("[{}] No search config defined", name);
            return Collections.emptyList();
        }

        try {
            String url = resolveTemplate(searchCfg.get("url").getAsString(),
                    Map.of("query", URLEncoder.encode(query, StandardCharsets.UTF_8)));

            if ("json".equals(type)) {
                return searchJson(url, searchCfg, limit);
            } else {
                return searchHtml(url, searchCfg, limit);
            }
        } catch (Exception e) {
            logger.error("[{}] Search failed for: {}", name, query, e);
            return Collections.emptyList();
        }
    }

    private List<MangaInfo> searchHtml(String url, JsonObject cfg, int limit) throws Exception {
        Document doc = fetchHtml(url);
        if (doc == null)
            return Collections.emptyList();

        List<MangaInfo> results = new ArrayList<>();
        Elements items = doc.select(cfg.get("itemSelector").getAsString());

        for (Element item : items) {
            if (results.size() >= limit)
                break;
            try {
                String title = extractText(item, cfg, "titleSelector", "Unknown");
                String linkHref = extractAttr(item, cfg, "linkSelector", "href", "");
                String coverUrl = extractAttr(item, cfg, "coverSelector",
                        optString(cfg, "coverAttr", "src"), null);
                String id = linkHref;

                // Extract ID via regex pattern if configured
                if (cfg.has("idPattern")) {
                    Matcher m = Pattern.compile(cfg.get("idPattern").getAsString()).matcher(linkHref);
                    if (m.find())
                        id = m.group(1);
                }

                if (coverUrl != null && coverUrl.startsWith("//"))
                    coverUrl = "https:" + coverUrl;
                if (coverUrl != null && coverUrl.startsWith("/"))
                    coverUrl = baseUrl + coverUrl;

                results.add(new MangaInfo(id, title, "", coverUrl, "", List.of(), getName()));
            } catch (Exception e) {
                logger.debug("[{}] Error parsing search item", name, e);
            }
        }
        return results;
    }

    private List<MangaInfo> searchJson(String url, JsonObject cfg, int limit) throws Exception {
        JsonObject response = fetchJson(url);
        if (response == null)
            return Collections.emptyList();

        List<MangaInfo> results = new ArrayList<>();
        JsonArray data = navigateJsonArray(response, optString(cfg, "dataPath", "data"));
        if (data == null)
            return Collections.emptyList();

        for (JsonElement el : data) {
            if (results.size() >= limit)
                break;
            try {
                JsonObject item = el.getAsJsonObject();
                String id = navigateJsonString(item, cfg.get("idPath").getAsString(), "");
                String title = navigateJsonString(item, cfg.get("titlePath").getAsString(), "Unknown");
                String author = cfg.has("authorPath")
                        ? navigateJsonString(item, cfg.get("authorPath").getAsString(), "")
                        : "";
                String coverUrl = cfg.has("coverPath")
                        ? navigateJsonString(item, cfg.get("coverPath").getAsString(), null)
                        : null;
                String description = cfg.has("descriptionPath")
                        ? navigateJsonString(item, cfg.get("descriptionPath").getAsString(), "")
                        : "";

                results.add(new MangaInfo(id, title, author, coverUrl, description, List.of(), getName()));
            } catch (Exception e) {
                logger.debug("[{}] Error parsing JSON search item", name, e);
            }
        }
        return results;
    }

    // ─── DETAILS ──────────────────────────────────────────────────────────

    @Override
    public MangaDetails getDetails(String mangaId) {
        JsonObject detailsCfg = config.getAsJsonObject("details");
        if (detailsCfg == null) {
            logger.warn("[{}] No details config defined", name);
            return null;
        }

        try {
            String url = resolveTemplate(detailsCfg.get("url").getAsString(),
                    Map.of("mangaId", mangaId));

            if ("json".equals(type)) {
                return getDetailsJson(url, detailsCfg, mangaId);
            } else {
                return getDetailsHtml(url, detailsCfg, mangaId);
            }
        } catch (Exception e) {
            logger.error("[{}] getDetails failed for: {}", name, mangaId, e);
            return null;
        }
    }

    private MangaDetails getDetailsHtml(String url, JsonObject cfg, String mangaId) throws Exception {
        Document doc = fetchHtml(url);
        if (doc == null)
            return null;

        String title = extractText(doc, cfg, "titleSelector", "Unknown");
        String coverUrl = extractAttr(doc, cfg, "coverSelector",
                optString(cfg, "coverAttr", "src"), null);
        String description = extractText(doc, cfg, "descriptionSelector", "");
        String author = extractText(doc, cfg, "authorSelector", "");

        List<String> tags = new ArrayList<>();
        if (cfg.has("tagsSelector")) {
            for (Element el : doc.select(cfg.get("tagsSelector").getAsString())) {
                tags.add(el.text().trim());
            }
        }

        if (coverUrl != null && coverUrl.startsWith("/"))
            coverUrl = baseUrl + coverUrl;

        MangaInfo info = new MangaInfo(mangaId, title, author, coverUrl, description, tags, getName());

        // Parse chapters
        List<ChapterInfo> chapters = new ArrayList<>();
        if (cfg.has("forceSingleChapter") && cfg.get("forceSingleChapter").getAsBoolean()) {
            // Create a single chapter that points to the manga itself (or a sub-path)
            chapters.add(new ChapterInfo(mangaId, "1", "Full Gallery", null, displayName, 0, getName()));
        } else if (cfg.has("chapterSelector")) {
            Elements chapterEls = doc.select(cfg.get("chapterSelector").getAsString());
            Pattern numPattern = cfg.has("chapterNumPattern")
                    ? Pattern.compile(cfg.get("chapterNumPattern").getAsString(), Pattern.CASE_INSENSITIVE)
                    : Pattern.compile("Chapter\\s+([\\d.]+)", Pattern.CASE_INSENSITIVE);

            for (Element ch : chapterEls) {
                try {
                    String chHref = ch.attr("href");
                    String chId = chHref;
                    if (chId.startsWith("/"))
                        chId = chId.substring(1);

                    // Apply chapter ID pattern if configured
                    if (cfg.has("chapterIdPattern")) {
                        Matcher m = Pattern.compile(cfg.get("chapterIdPattern").getAsString()).matcher(chHref);
                        if (m.find())
                            chId = m.group(1);
                    }

                    String chText = ch.text().trim();
                    String chapterNum = "0";
                    Matcher m = numPattern.matcher(chText);
                    if (m.find())
                        chapterNum = m.group(1);

                    chapters.add(new ChapterInfo(chId, chapterNum, chText, null, displayName, 0, getName()));
                } catch (Exception e) {
                    logger.debug("[{}] Error parsing chapter", name, e);
                }
            }

            // Handle chapter ordering
            String order = optString(cfg, "chapterOrder", "asc");
            if ("desc".equals(order)) {
                Collections.reverse(chapters);
            }
        }

        return new MangaDetails(info, chapters);
    }

    private MangaDetails getDetailsJson(String url, JsonObject cfg, String mangaId) throws Exception {
        JsonObject response = fetchJson(url);
        if (response == null)
            return null;

        // Navigate to the data object if needed
        JsonObject data = response;
        if (cfg.has("dataPath")) {
            JsonElement nav = navigateJson(response, cfg.get("dataPath").getAsString());
            if (nav != null && nav.isJsonObject())
                data = nav.getAsJsonObject();
        }

        String title = navigateJsonString(data, optString(cfg, "titlePath", "title"), "Unknown");
        String author = navigateJsonString(data, optString(cfg, "authorPath", "author"), "");
        String coverUrl = cfg.has("coverPath")
                ? navigateJsonString(data, cfg.get("coverPath").getAsString(), null)
                : null;
        String description = navigateJsonString(data, optString(cfg, "descriptionPath", "description"), "");

        MangaInfo info = new MangaInfo(mangaId, title, author, coverUrl, description, List.of(), getName());

        // Parse chapters from JSON
        List<ChapterInfo> chapters = new ArrayList<>();
        if (cfg.has("chaptersPath")) {
            JsonArray chaptersArr = navigateJsonArray(response, cfg.get("chaptersPath").getAsString());
            if (chaptersArr != null) {
                for (JsonElement el : chaptersArr) {
                    JsonObject ch = el.getAsJsonObject();
                    String chId = navigateJsonString(ch, optString(cfg, "chapterIdPath", "id"), "");
                    String chNum = navigateJsonString(ch, optString(cfg, "chapterNumPath", "chapter"), "0");
                    String chTitle = navigateJsonString(ch, optString(cfg, "chapterTitlePath", "title"), null);
                    chapters.add(new ChapterInfo(chId, chNum, chTitle, null, displayName, 0, getName()));
                }
            }
        }

        return new MangaDetails(info, chapters);
    }

    // ─── CHAPTER PAGES ────────────────────────────────────────────────────

    @Override
    public List<String> getChapterPages(String chapterId) {
        JsonObject pagesCfg = config.getAsJsonObject("pages");
        if (pagesCfg == null) {
            logger.warn("[{}] No pages config defined", name);
            return Collections.emptyList();
        }

        try {
            String url = resolveTemplate(pagesCfg.get("url").getAsString(),
                    Map.of("chapterId", chapterId));

            if ("json".equals(type)) {
                return getChapterPagesJson(url, pagesCfg);
            } else {
                return getChapterPagesHtml(url, pagesCfg);
            }
        } catch (Exception e) {
            logger.error("[{}] getChapterPages failed for: {}", name, chapterId, e);
            return Collections.emptyList();
        }
    }

    private List<String> getChapterPagesHtml(String url, JsonObject cfg) throws Exception {
        Document doc = fetchHtml(url);
        if (doc == null)
            return Collections.emptyList();

        List<String> pages = new ArrayList<>();
        String srcAttr = optString(cfg, "srcAttr", "src");
        Elements images = doc.select(cfg.get("imageSelector").getAsString());

        for (Element img : images) {
            String src = img.attr(srcAttr);
            if (src.isEmpty())
                src = img.attr("data-src");
            if (src.isEmpty())
                src = img.attr("src");
            if (!src.isEmpty()) {
                if (src.startsWith("//"))
                    src = "https:" + src;
                if (src.startsWith("/"))
                    src = baseUrl + src;
                pages.add(src);
            }
        }

        logger.info("[{}] Found {} pages for chapter", name, pages.size());
        return pages;
    }

    private List<String> getChapterPagesJson(String url, JsonObject cfg) throws Exception {
        JsonObject response = fetchJson(url);
        if (response == null)
            return Collections.emptyList();

        List<String> pages = new ArrayList<>();
        String imagesPath = optString(cfg, "imagesPath", "images");
        JsonArray imagesArr = navigateJsonArray(response, imagesPath);
        if (imagesArr == null)
            return Collections.emptyList();

        String urlFieldPath = optString(cfg, "imageUrlPath", null);
        String baseImageUrl = cfg.has("baseImageUrl") ? cfg.get("baseImageUrl").getAsString() : "";

        for (JsonElement el : imagesArr) {
            String pageUrl;
            if (el.isJsonPrimitive()) {
                pageUrl = el.getAsString();
            } else if (urlFieldPath != null) {
                pageUrl = navigateJsonString(el.getAsJsonObject(), urlFieldPath, "");
            } else {
                continue;
            }
            if (!pageUrl.isEmpty()) {
                if (!pageUrl.startsWith("http"))
                    pageUrl = baseImageUrl + pageUrl;
                pages.add(pageUrl);
            }
        }

        logger.info("[{}] Found {} pages for chapter", name, pages.size());
        return pages;
    }

    // ─── TEMPLATE ENGINE ──────────────────────────────────────────────────

    private String resolveTemplate(String template, Map<String, String> vars) {
        String result = template.replace("${baseUrl}", baseUrl);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    // ─── HTTP HELPERS ─────────────────────────────────────────────────────

    private Document fetchHtml(String url) {
        try {
            var conn = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(15000);

            for (Map.Entry<String, String> h : defaultHeaders.entrySet()) {
                conn.header(h.getKey(), h.getValue());
            }

            return conn.get();
        } catch (Exception e) {
            logger.error("[{}] HTML fetch failed: {}", name, url, e);
            return null;
        }
    }

    private JsonObject fetchJson(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            for (Map.Entry<String, String> h : defaultHeaders.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }

            if (conn.getResponseCode() != 200) {
                logger.warn("[{}] API returned {}: {}", name, conn.getResponseCode(), url);
                return null;
            }

            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                return gson.fromJson(reader, JsonObject.class);
            }
        } catch (Exception e) {
            logger.error("[{}] JSON fetch failed: {}", name, url, e);
            return null;
        }
    }

    // ─── EXTRACTION HELPERS ───────────────────────────────────────────────

    private String extractText(Element root, JsonObject cfg, String key, String fallback) {
        if (!cfg.has(key))
            return fallback;

        JsonElement info = cfg.get(key);
        if (info.isJsonPrimitive()) {
            Element el = root.selectFirst(info.getAsString());
            return el != null ? el.text().trim() : fallback;
        } else if (info.isJsonObject()) {
            JsonObject obj = info.getAsJsonObject();
            Element el = root.selectFirst(obj.get("selector").getAsString());
            if (el == null)
                return fallback;

            String val = el.text().trim();
            return applyTransforms(val, obj);
        }
        return fallback;
    }

    private String extractAttr(Element root, JsonObject cfg, String key, String defaultAttr, String fallback) {
        if (!cfg.has(key))
            return fallback;

        JsonElement info = cfg.get(key);
        String selector;
        String attr = defaultAttr;
        JsonObject transformObj = null;

        if (info.isJsonPrimitive()) {
            selector = info.getAsString();
        } else if (info.isJsonObject()) {
            JsonObject obj = info.getAsJsonObject();
            selector = obj.get("selector").getAsString();
            if (obj.has("attr"))
                attr = obj.get("attr").getAsString();
            transformObj = obj;
        } else {
            return fallback;
        }

        Element el = root.selectFirst(selector);
        if (el == null)
            return fallback;

        String val = el.attr(attr);
        if (val.isEmpty())
            return fallback; // Try to avoid empty attrs

        return applyTransforms(val, transformObj);
    }

    private String applyTransforms(String value, JsonObject cfg) {
        if (cfg == null || value == null)
            return value;

        String result = value;
        if (cfg.has("regex") && cfg.has("replace")) {
            try {
                result = result.replaceAll(cfg.get("regex").getAsString(), cfg.get("replace").getAsString());
            } catch (Exception e) {
                logger.error("[{}] Regex transform failed: {}", name, e.getMessage());
            }
        }
        return result;
    }

    // ─── JSON NAVIGATION ──────────────────────────────────────────────────

    /**
     * Navigate a JSON object using dot-separated path, e.g.
     * "data.attributes.title.en"
     */
    private JsonElement navigateJson(JsonObject root, String path) {
        String[] parts = path.split("\\.");
        JsonElement current = root;
        for (String part : parts) {
            if (current == null || !current.isJsonObject())
                return null;
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    private String navigateJsonString(JsonObject root, String path, String fallback) {
        JsonElement el = navigateJson(root, path);
        if (el == null || el.isJsonNull())
            return fallback;
        return el.getAsString();
    }

    private JsonArray navigateJsonArray(JsonObject root, String path) {
        JsonElement el = navigateJson(root, path);
        if (el == null || !el.isJsonArray())
            return null;
        return el.getAsJsonArray();
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? obj.get(key).getAsString() : fallback;
    }
}
