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

            return parseMangaGrid(doc, limit);
        } catch (Exception e) {
            logger.error("MangaPill search failed for: {}", query, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> getGenres() {
        try {
            Document doc = fetchDocument(BASE_URL + "/search");
            if (doc == null) return Collections.emptyList();

            List<String> genres = new ArrayList<>();
            Elements genreLinks = doc.select("a[href*='genre=']");
            for (Element link : genreLinks) {
                String genre = link.text().trim();
                if (!genre.isEmpty() && !genres.contains(genre)) {
                    genres.add(genre);
                }
            }
            
            if (genres.isEmpty()) {
                return List.of("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery", "Psychological", "Romance", "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Sports", "Supernatural", "Thriller", "Tragedy");
            }

            Collections.sort(genres);
            return genres;
        } catch (Exception e) {
            logger.error("MangaPill getGenres failed", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<MangaInfo> getByGenre(String genre, int limit, int page) {
        try {
            String url = BASE_URL + "/search?genre=" + java.net.URLEncoder.encode(genre, java.nio.charset.StandardCharsets.UTF_8);
            if (page > 1) {
                url += "&page=" + page;
            }
            Document doc = fetchDocument(url);
            if (doc == null) return Collections.emptyList();
            return parseMangaGrid(doc, limit);
        } catch (Exception e) {
            logger.error("MangaPill getByGenre failed for: {} (page {})", genre, page, e);
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
            Element coverImg = doc.selectFirst("img[data-src*=mangapill], img[src*=mangapill], div.relative img, img.object-cover");
            String coverUrl = null;
            if (coverImg != null) {
                coverUrl = coverImg.hasAttr("data-src") ? coverImg.attr("data-src") : coverImg.attr("src");
            }

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
    public List<MangaInfo> getPopular(int limit) {
        Document doc = fetchDocument(BASE_URL);
        if (doc == null) return Collections.emptyList();

        // Target the "Trending Mangas" section precisely
        Element trendingHeader = null;
        Elements headers = doc.select("h1, h2, h3, h4");
        for (Element h : headers) {
            if (h.text().toLowerCase().contains("trending mang")) {
                trendingHeader = h;
                break;
            }
        }

        if (trendingHeader != null) {
            // Usually the header is in a flex container with "Surprise Me" button
            // The grid is the NEXT sibling of that container.
            Element container = trendingHeader.parent();
            Element grid = container.nextElementSibling();
            
            // Skip potential ads or non-div siblings
            while (grid != null && (!grid.tagName().equals("div") || (!grid.hasClass("grid") && grid.selectFirst("div.grid") == null))) {
                grid = grid.nextElementSibling();
            }

            if (grid != null) {
                // If the grid is a descendant of the container
                if (!grid.hasClass("grid")) {
                    grid = grid.selectFirst("div.grid");
                }
                if (grid != null) {
                    return parseSpecificGrid(grid, limit);
                }
            }
        }

        // Fallback: parse regular grids but prioritize first
        return parseMangaGrid(doc, limit);
    }

    private List<MangaInfo> parseSpecificGrid(Element grid, int limit) {
        List<MangaInfo> results = new ArrayList<>();
        Elements cards = grid.children();
        for (Element card : cards) {
            if (results.size() >= limit) break;
            
            // Exclude chapter-specific updates
            boolean isChapterCard = card.selectFirst("a[href^=/chapters/]") != null;
            if (isChapterCard) continue;

            MangaInfo info = parseCard(card);
            if (info != null) results.add(info);
        }
        return results;
    }

    private MangaInfo parseCard(Element card) {
        try {
            // Find the link that points to the manga page
            Element titleLink = card.selectFirst("a[href^=/manga/]");
            if (titleLink == null) return null;

            String href = titleLink.attr("href");
            String mangaId = extractMangaId(href);
            if (mangaId == null) return null;

            Element img = card.selectFirst("img");
            String coverUrl = null;
            if (img != null) {
                coverUrl = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
            }

            // Title is usually inside a div within an <a> tag, or directly in an <a> tag
            // We look at ALL links in the card to find one with text
            String title = "";
            Elements allLinks = card.select("a[href^=/manga/]");
            for (Element link : allLinks) {
                // Try to find a nested div with text first
                Element div = link.selectFirst("div");
                if (div != null && !div.text().trim().isEmpty()) {
                    title = div.text().trim();
                    break;
                }
                // Try the link text itself
                if (!link.text().trim().isEmpty()) {
                    title = link.text().trim();
                    break;
                }
            }

            // Fallback for different structures
            if (title.isEmpty()) {
                Element titleEl = card.selectFirst("a.mb-2, a.font-bold, div.font-bold, h4, h5");
                if (titleEl != null) title = titleEl.text().trim();
            }

            List<String> tags = new ArrayList<>();
            // Metadata like Year, Status, etc.
            Elements metaEls = card.select("div.text-xs, div.text-muted, div.flex.flex-wrap div");
            for (Element meta : metaEls) {
                String t = meta.text().trim();
                if (!t.isEmpty() && !t.startsWith("#") && t.length() < 30) {
                    tags.add(t);
                }
            }

            return new MangaInfo(mangaId, title, "", coverUrl, "", tags, getName());
        } catch (Exception e) {
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

    @Override
    public MangaInfo getRandom() {
        try {
            // MangaPill /random redirects to a random manga page
            String url = BASE_URL + "/mangas/random";
            // We need to follow redirects manually or use a connection that does
            org.jsoup.Connection.Response response = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Referer", BASE_URL)
                    .followRedirects(true)
                    .execute();
            
            Document doc = response.parse();
            String finalUrl = response.url().toString();
            String mangaId = extractMangaId(finalUrl);
            
            if (mangaId != null) {
                // Now parse the details page we landed on
                Element titleEl = doc.selectFirst("h1");
                String title = titleEl != null ? titleEl.text().trim() : "Unknown";
                
                Element coverImg = doc.selectFirst("img[data-src*=mangapill], img[src*=mangapill], div.relative img, img.object-cover");
                String coverUrl = null;
                if (coverImg != null) {
                    coverUrl = coverImg.hasAttr("data-src") ? coverImg.attr("data-src") : coverImg.attr("src");
                }
                
                return new MangaInfo(mangaId, title, "", coverUrl, "", List.of(), getName());
            }
        } catch (Exception e) {
            logger.error("MangaPill getRandom failed", e);
        }
        return null;
    }

    private List<MangaInfo> parseMangaGrid(Document doc, int limit) {
        List<MangaInfo> results = new ArrayList<>();
        Elements grids = doc.select("div.grid");

        for (Element grid : grids) {
            Elements cards = grid.children();
            for (Element card : cards) {
                if (results.size() >= limit) break;
                try {
                    // A valid manga card has a link to /manga/
                    Element titleLink = card.selectFirst("a.mb-2, a[href^=/manga/]");
                    if (titleLink == null) continue;

                    String href = titleLink.attr("href");
                    String mangaId = extractMangaId(href);
                    if (mangaId == null) continue;

                    // Cover
                    Element img = card.selectFirst("a.relative.block img, img");
                    String coverUrl = null;
                    if (img != null) {
                        coverUrl = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
                    }

                    // Title
                    Element titleEl = titleLink.selectFirst("div");
                    String title = (titleEl != null) ? titleEl.text().trim() : titleLink.text().trim();
                    if (title.isEmpty() || title.equalsIgnoreCase("Unknown")) {
                        if (img != null && img.hasAttr("alt")) title = img.attr("alt").trim();
                    }

                    // Tags
                    List<String> tags = new ArrayList<>();
                    Elements tagEls = card.select("div.flex.flex-wrap.gap-1 div");
                    for (Element tag : tagEls) {
                        String t = tag.text().trim();
                        if (!t.isEmpty()) tags.add(t);
                    }

                    results.add(new MangaInfo(
                            mangaId, title, "", coverUrl, "", tags, getName()
                    ));
                } catch (Exception e) {
                    logger.debug("Error parsing manga card", e);
                }
            }
            if (results.size() >= limit) break;
        }
        return results;
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
