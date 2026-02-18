package com.plugins.manga.internal.providers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.plugins.manga.internal.MangaProvider;
import com.plugins.manga.internal.model.ChapterInfo;
import com.plugins.manga.internal.model.MangaDetails;
import com.plugins.manga.internal.model.MangaInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MangaDex API v5 client.
 * Docs: https://api.mangadex.org/docs/
 */
public class MangaDexProvider implements MangaProvider {
    private static final Logger logger = LoggerFactory.getLogger(MangaDexProvider.class);
    private static final String BASE_URL = "https://api.mangadex.org";
    private static final String COVER_BASE = "https://uploads.mangadex.org/covers";
    private static final String USER_AGENT = "MediaAutomationFramework/1.0 MangaPlugin";
    private final Gson gson = new Gson();

    @Override
    public String getName() {
        return "mangadex";
    }

    @Override
    public String getDisplayName() {
        return "MangaDex";
    }

    @Override
    public List<MangaInfo> search(String query, int limit) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = BASE_URL + "/manga?title=" + encoded
                    + "&limit=" + Math.min(limit, 25)
                    + "&includes[]=cover_art&includes[]=author"
                    + "&order[relevance]=desc"
                    + "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica";

            JsonObject response = getJson(url);
            if (response == null) return Collections.emptyList();

            JsonArray data = response.getAsJsonArray("data");
            List<MangaInfo> results = new ArrayList<>();

            for (JsonElement el : data) {
                JsonObject manga = el.getAsJsonObject();
                results.add(parseMangaInfo(manga));
            }

            return results;
        } catch (Exception e) {
            logger.error("MangaDex search failed for: {}", query, e);
            return Collections.emptyList();
        }
    }

    @Override
    public MangaDetails getDetails(String mangaId) {
        try {
            // Get manga info
            String url = BASE_URL + "/manga/" + mangaId + "?includes[]=cover_art&includes[]=author";
            JsonObject response = getJson(url);
            if (response == null) return null;

            MangaInfo info = parseMangaInfo(response.getAsJsonObject("data"));

            // Get chapters (English only, sorted by chapter number)
            List<ChapterInfo> chapters = fetchAllChapters(mangaId);

            return new MangaDetails(info, chapters);
        } catch (Exception e) {
            logger.error("MangaDex getDetails failed for: {}", mangaId, e);
            return null;
        }
    }

    @Override
    public List<String> getChapterPages(String chapterId) {
        try {
            String url = BASE_URL + "/at-home/server/" + chapterId;
            JsonObject response = getJson(url);
            if (response == null) return Collections.emptyList();

            String baseUrl = response.get("baseUrl").getAsString();
            JsonObject chapter = response.getAsJsonObject("chapter");
            String hash = chapter.get("hash").getAsString();
            JsonArray pages = chapter.getAsJsonArray("data");

            List<String> urls = new ArrayList<>();
            for (JsonElement page : pages) {
                urls.add(baseUrl + "/data/" + hash + "/" + page.getAsString());
            }
            return urls;
        } catch (Exception e) {
            logger.error("MangaDex getChapterPages failed for: {}", chapterId, e);
            return Collections.emptyList();
        }
    }

    // --- Internal Helpers ---

    private List<ChapterInfo> fetchAllChapters(String mangaId) {
        List<ChapterInfo> allChapters = new ArrayList<>();
        int offset = 0;
        int limit = 100;
        int total = Integer.MAX_VALUE;

        while (offset < total) {
            try {
                String url = BASE_URL + "/manga/" + mangaId + "/feed"
                        + "?translatedLanguage[]=en"
                        + "&order[chapter]=asc"
                        + "&limit=" + limit
                        + "&offset=" + offset
                        + "&includes[]=scanlation_group";

                JsonObject response = getJson(url);
                if (response == null) break;

                total = response.get("total").getAsInt();
                JsonArray data = response.getAsJsonArray("data");

                for (JsonElement el : data) {
                    JsonObject ch = el.getAsJsonObject();
                    JsonObject attrs = ch.getAsJsonObject("attributes");

                    // Skip external chapters (e.g. MangaPlus links) which can't be read in-app
                    if (attrs.has("externalUrl") && !attrs.get("externalUrl").isJsonNull()) {
                        continue;
                    }

                    String scanlationGroup = "Unknown";
                    JsonArray relationships = ch.getAsJsonArray("relationships");
                    if (relationships != null) {
                        for (JsonElement rel : relationships) {
                            JsonObject r = rel.getAsJsonObject();
                            if ("scanlation_group".equals(r.get("type").getAsString())) {
                                JsonObject relAttrs = r.getAsJsonObject("attributes");
                                if (relAttrs != null && relAttrs.has("name")) {
                                    scanlationGroup = relAttrs.get("name").getAsString();
                                }
                            }
                        }
                    }

                    allChapters.add(new ChapterInfo(
                            ch.get("id").getAsString(),
                            attrs.has("chapter") && !attrs.get("chapter").isJsonNull()
                                    ? attrs.get("chapter").getAsString() : "0",
                            attrs.has("title") && !attrs.get("title").isJsonNull()
                                    ? attrs.get("title").getAsString() : null,
                            attrs.has("volume") && !attrs.get("volume").isJsonNull()
                                    ? attrs.get("volume").getAsString() : null,
                            scanlationGroup,
                            attrs.has("publishAt") ? parseTimestamp(attrs.get("publishAt").getAsString()) : 0,
                            getName()
                    ));
                }

                offset += limit;

                // Rate limiting - MangaDex asks for max 5 req/s
                Thread.sleep(250);
            } catch (Exception e) {
                logger.error("Error fetching chapters at offset {}", offset, e);
                break;
            }
        }

        return allChapters;
    }

    private MangaInfo parseMangaInfo(JsonObject manga) {
        JsonObject attrs = manga.getAsJsonObject("attributes");

        // Title (prefer English)
        String title = "Unknown";
        JsonObject titleObj = attrs.getAsJsonObject("title");
        if (titleObj.has("en")) {
            title = titleObj.get("en").getAsString();
        } else if (titleObj.has("ja-ro")) {
            title = titleObj.get("ja-ro").getAsString();
        } else if (!titleObj.entrySet().isEmpty()) {
            title = titleObj.entrySet().iterator().next().getValue().getAsString();
        }

        // Description
        String description = "";
        JsonObject descObj = attrs.getAsJsonObject("description");
        if (descObj != null && descObj.has("en")) {
            description = descObj.get("en").getAsString();
        }

        // Author & Cover from relationships
        String author = "Unknown";
        String coverFileName = null;
        JsonArray relationships = manga.getAsJsonArray("relationships");
        if (relationships != null) {
            for (JsonElement rel : relationships) {
                JsonObject r = rel.getAsJsonObject();
                String type = r.get("type").getAsString();
                JsonObject relAttrs = r.has("attributes") ? r.getAsJsonObject("attributes") : null;

                if ("author".equals(type) && relAttrs != null && relAttrs.has("name")) {
                    author = relAttrs.get("name").getAsString();
                }
                if ("cover_art".equals(type) && relAttrs != null && relAttrs.has("fileName")) {
                    coverFileName = relAttrs.get("fileName").getAsString();
                }
            }
        }

        // Cover URL
        String coverUrl = coverFileName != null
                ? COVER_BASE + "/" + manga.get("id").getAsString() + "/" + coverFileName + ".256.jpg"
                : null;

        // Tags
        List<String> tags = new ArrayList<>();
        JsonArray tagsArr = attrs.getAsJsonArray("tags");
        if (tagsArr != null) {
            for (JsonElement t : tagsArr) {
                JsonObject tagAttrs = t.getAsJsonObject().getAsJsonObject("attributes");
                JsonObject nameObj = tagAttrs.getAsJsonObject("name");
                if (nameObj.has("en")) {
                    tags.add(nameObj.get("en").getAsString());
                }
            }
        }

        return new MangaInfo(
                manga.get("id").getAsString(),
                title,
                author,
                coverUrl,
                description,
                tags,
                getName()
        );
    }

    private JsonObject getJson(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            if (conn.getResponseCode() != 200) {
                logger.warn("MangaDex API returned {}: {}", conn.getResponseCode(), urlStr);
                return null;
            }

            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                return gson.fromJson(reader, JsonObject.class);
            }
        } catch (Exception e) {
            logger.error("HTTP GET failed: {}", urlStr, e);
            return null;
        }
    }

    private long parseTimestamp(String iso8601) {
        try {
            return java.time.Instant.parse(iso8601).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
