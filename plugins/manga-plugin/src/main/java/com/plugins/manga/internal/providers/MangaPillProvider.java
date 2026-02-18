package com.plugins.manga.internal.providers;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MangaPill provider — scrapes mangapill.com for manga data.
 */
public class MangaPillProvider implements MangaProvider {
    private static final Logger logger = LoggerFactory.getLogger(MangaPillProvider.class);
    private static final String BASE_URL = "https://mangapill.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final Pattern CHAPTER_NUM_PATTERN = Pattern.compile("Chapter\\s+([\\d.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MANGA_ID_PATTERN = Pattern.compile("/manga/(\\d+)/");

    @Override
    public String getName() {
        return "mangapill";
    }

    @Override
    public String getDisplayName() {
        return "MangaPill";
    }

    @Override
    public List<MangaInfo> search(String query, int limit) {
        try {
            String url = BASE_URL + "/search?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            Document doc = fetchDocument(url);
            if (doc == null) return Collections.emptyList();

            List<MangaInfo> results = new ArrayList<>();
            Elements mangaCards = doc.select("div.my-3.justify-end > div");

            for (Element card : mangaCards) {
                if (results.size() >= limit) break;
                try {
                    Element link = card.selectFirst("a[href*=/manga/]");
                    if (link == null) continue;

                    String href = link.attr("href");
                    String mangaId = extractMangaId(href);
                    if (mangaId == null) continue;

                    // Cover image
                    Element img = card.selectFirst("img");
                    String coverUrl = img != null ? img.attr("src") : null;

                    // Title
                    Element titleEl = card.selectFirst("a.mt-3, div.mt-3 a, a[href*=/manga/] + div a, a.font-black");
                    String title = "Unknown";
                    if (titleEl != null) {
                        title = titleEl.text().trim();
                    } else if (link.text() != null && !link.text().isBlank()) {
                        title = link.text().trim();
                    }

                    // Fallback: use img alt
                    if ("Unknown".equals(title) && img != null && img.hasAttr("alt")) {
                        title = img.attr("alt").trim();
                    }

                    results.add(new MangaInfo(
                            mangaId, title, "", coverUrl, "", List.of(), getName()
                    ));
                } catch (Exception e) {
                    logger.debug("Error parsing manga card", e);
                }
            }

            return results;
        } catch (Exception e) {
            logger.error("MangaPill search failed for: {}", query, e);
            return Collections.emptyList();
        }
    }

    @Override
    public MangaDetails getDetails(String mangaId) {
        try {
            String url = BASE_URL + "/manga/" + mangaId;
            Document doc = fetchDocument(url);
            if (doc == null) return null;

            // Title
            Element titleEl = doc.selectFirst("h1");
            String title = titleEl != null ? titleEl.text().trim() : "Unknown";

            // Cover
            Element coverImg = doc.selectFirst("img[src*=cover], div.relative img");
            String coverUrl = coverImg != null ? coverImg.attr("src") : null;

            // Description
            Element descEl = doc.selectFirst("p.text-sm.text--secondary, div.my-3 p");
            String description = descEl != null ? descEl.text().trim() : "";

            // Author
            String author = "";
            Elements infoLinks = doc.select("div.grid a[href*=/search]");
            if (!infoLinks.isEmpty()) {
                author = infoLinks.first().text().trim();
            }

            // Tags / Genres
            List<String> tags = new ArrayList<>();
            Elements genreLinks = doc.select("a[href*=/genre/]");
            for (Element g : genreLinks) {
                tags.add(g.text().trim());
            }

            MangaInfo info = new MangaInfo(mangaId, title, author, coverUrl, description, tags, getName());

            // Chapters
            List<ChapterInfo> chapters = new ArrayList<>();
            Elements chapterLinks = doc.select("a[href*=/chapters/]");

            for (Element ch : chapterLinks) {
                try {
                    String chHref = ch.attr("href");
                    String chId = chHref; // Use full path as ID
                    if (chId.startsWith("/")) chId = chId.substring(1);

                    String chText = ch.text().trim();
                    String chapterNum = "0";
                    Matcher m = CHAPTER_NUM_PATTERN.matcher(chText);
                    if (m.find()) {
                        chapterNum = m.group(1);
                    }

                    chapters.add(new ChapterInfo(
                            chId, chapterNum, chText, null, "MangaPill", 0, getName()
                    ));
                } catch (Exception e) {
                    logger.debug("Error parsing chapter link", e);
                }
            }

            // MangaPill lists newest first, reverse for ascending order
            Collections.reverse(chapters);

            return new MangaDetails(info, chapters);
        } catch (Exception e) {
            logger.error("MangaPill getDetails failed for: {}", mangaId, e);
            return null;
        }
    }

    @Override
    public List<String> getChapterPages(String chapterId) {
        try {
            String url = BASE_URL + "/" + chapterId;
            Document doc = fetchDocument(url);
            if (doc == null) return Collections.emptyList();

            List<String> pages = new ArrayList<>();
            Elements images = doc.select("chapter-page img, img[chapter-page], picture img");

            // Fallback: look for images in the chapter reader container
            if (images.isEmpty()) {
                images = doc.select("div.container img[src*=manga], img.js-page");
            }

            for (Element img : images) {
                String src = img.attr("src");
                if (src.isEmpty()) src = img.attr("data-src");
                if (!src.isEmpty()) {
                    if (src.startsWith("//")) src = "https:" + src;
                    pages.add(src);
                }
            }

            logger.info("MangaPill found {} pages for chapter: {}", pages.size(), chapterId);
            return pages;
        } catch (Exception e) {
            logger.error("MangaPill getChapterPages failed for: {}", chapterId, e);
            return Collections.emptyList();
        }
    }

    // --- Helpers ---

    private Document fetchDocument(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Referer", BASE_URL)
                    .timeout(15000)
                    .get();
        } catch (Exception e) {
            logger.error("Failed to fetch: {}", url, e);
            return null;
        }
    }

    private String extractMangaId(String href) {
        // Extract numeric ID from paths like /manga/12345/manga-title
        Matcher m = MANGA_ID_PATTERN.matcher(href + "/");
        if (m.find()) return m.group(1);

        // Fallback: use the full path segment
        if (href.startsWith("/manga/")) {
            return href.substring("/manga/".length());
        }
        return null;
    }
}
