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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NHentaiProvider implements MangaProvider {
    private static final Logger logger = LoggerFactory.getLogger(NHentaiProvider.class);
    private final HttpClient client;
    private final Gson gson;
    private static final String BASE_URL = "https://nhentai.net";
    private static final String API_BASE = "https://nhentai.net/api";
    private static final String IMAGE_BASE = "https://i.nhentai.net/galleries";

    public NHentaiProvider() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.gson = new Gson();
    }

    @Override
    public String getName() {
        return "nhentai";
    }

    @Override
    public String getDisplayName() {
        return "nhentai";
    }

    @Override
    public boolean isNsfw() {
        return true;
    }

    @Override
    public List<MangaInfo> search(String query, int limit) {
        List<MangaInfo> results = new ArrayList<>();
        try {
            String url = API_BASE + "/galleries/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            String json = fetch(url);
            JsonObject response = gson.fromJson(json, JsonObject.class);

            if (response.has("result")) {
                JsonArray arr = response.getAsJsonArray("result");
                for (JsonElement el : arr) {
                    if (results.size() >= limit)
                        break;
                    results.add(mapToInfo(el.getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to search nhentai: {}", e.getMessage());
        }
        return results;
    }

    @Override
    public MangaDetails getDetails(String mangaId) {
        try {
            String url = API_BASE + "/gallery/" + mangaId;
            String json = fetch(url);
            JsonObject obj = gson.fromJson(json, JsonObject.class);

            MangaInfo info = mapToInfo(obj);

            // nhentai is one-shot, so we create a single chapter
            long uploadDate = obj.has("upload_date") ? obj.get("upload_date").getAsLong() * 1000
                    : System.currentTimeMillis();

            ChapterInfo chapter = new ChapterInfo(
                    mangaId, // Use mangaId as chapterId for the single chapter
                    "1",
                    "Full Gallery",
                    "1",
                    "nhentai",
                    uploadDate,
                    getName());

            return new MangaDetails(info, Collections.singletonList(chapter));

        } catch (Exception e) {
            logger.error("Failed to get details for nhentai id {}: {}", mangaId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> getChapterPages(String chapterId) {
        // Since we mapped mangaId -> chapterId, we just fetch the gallery again to get
        // pages
        List<String> pages = new ArrayList<>();
        try {
            String url = API_BASE + "/gallery/" + chapterId;
            String json = fetch(url);
            JsonObject obj = gson.fromJson(json, JsonObject.class);

            String mediaId = obj.get("media_id").getAsString();
            JsonArray images = obj.getAsJsonObject("images").getAsJsonArray("pages");

            for (int i = 0; i < images.size(); i++) {
                JsonObject img = images.get(i).getAsJsonObject();
                String ext = getExtension(img.get("t").getAsString());
                // format: https://i.nhentai.net/galleries/{media_id}/{page}.{ext}
                pages.add(String.format("%s/%s/%d.%s", IMAGE_BASE, mediaId, i + 1, ext));
            }

        } catch (Exception e) {
            logger.error("Failed to get pages for nhentai id {}: {}", chapterId, e.getMessage());
        }
        return pages;
    }

    private MangaInfo mapToInfo(JsonObject obj) {
        String id = String.valueOf(obj.get("id").getAsInt());
        String mediaId = obj.get("media_id").getAsString();

        JsonObject titleObj = obj.getAsJsonObject("title");
        String title = titleObj.has("english") && !titleObj.get("english").isJsonNull()
                ? titleObj.get("english").getAsString()
                : titleObj.get("pretty").getAsString();

        // Extract tags
        List<String> tags = new ArrayList<>();
        if (obj.has("tags")) {
            for (JsonElement t : obj.getAsJsonArray("tags")) {
                tags.add(t.getAsJsonObject().get("name").getAsString());
            }
        }

        // Extract author/artist from tags if possible, or use first tag
        String author = "Unknown";
        for (JsonElement t : obj.getAsJsonArray("tags")) {
            JsonObject tag = t.getAsJsonObject();
            if ("artist".equals(tag.get("type").getAsString()) || "group".equals(tag.get("type").getAsString())) {
                author = tag.get("name").getAsString();
                break;
            }
        }

        // Cover image (thumbnail)
        // thumb format: https://t.nhentai.net/galleries/{media_id}/thumb.{ext}
        // cover format: https://t.nhentai.net/galleries/{media_id}/cover.{ext}
        String coverExt = "jpg";
        if (obj.getAsJsonObject("images").getAsJsonObject("cover").has("t")) {
            coverExt = getExtension(obj.getAsJsonObject("images").getAsJsonObject("cover").get("t").getAsString());
        }
        String coverUrl = String.format("https://t.nhentai.net/galleries/%s/cover.%s", mediaId, coverExt);

        return new MangaInfo(id, title, author, coverUrl, "Doujinshi", tags, getName());
    }

    private String getExtension(String type) {
        switch (type) {
            case "j":
                return "jpg";
            case "p":
                return "png";
            case "g":
                return "gif";
            default:
                return "jpg";
        }
    }

    private String fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return response.body();
    }
}
