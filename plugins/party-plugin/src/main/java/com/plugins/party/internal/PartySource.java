package com.plugins.party.internal;

import com.framework.api.MediaSource;
import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class PartySource implements MediaSource {
    private static final Logger logger = LoggerFactory.getLogger(PartySource.class);
    private final Kernel kernel;
    private final Gson gson = new Gson();

    // Domains (Aktuelle Stand 2025/2026)
    private static final String COOMER_API = "https://coomer.st/api/v1";
    private static final String COOMER_CDN = "https://n1.coomer.st";
    private static final String KEMONO_API = "https://kemono.cr/api/v1";
    private static final String KEMONO_CDN = "https://n1.kemono.cr";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    // --- CACHING VARIABLES ---
    // Wir speichern die Listen im RAM, um nicht jedes Mal 10MB+ laden zu müssen
    private List<Creator> cacheCoomer = null;
    private List<Creator> cacheKemono = null;
    private long timeCoomer = 0;
    private long timeKemono = 0;
    private static final long CACHE_LIFETIME = 1000 * 60 * 60; // 1 Stunde Cache

    public record Creator(String id, String name, String service) {
    }

    public record Post(String id, String service, String user, String title, String content, FileInfo file,
            List<FileInfo> attachments, String published) {
    }

    public record FileInfo(String name, String path) {
    }

    public PartySource(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public String getName() {
        return "PartySource";
    }

    @Override
    public void execute(QueueTask task) {
        String query = task.getString("query");
        if (query == null)
            return;

        int targetPostCount = task.getInt("amount", 1);
        String source = task.getString("source");

        // Check if this is explicitly a "random" search
        boolean isRandom = query.equalsIgnoreCase("random");

        // MediaChat-style auto-source detection: try Coomer first, then Kemono
        boolean isKemono = "kemono".equalsIgnoreCase(source);

        if (!isRandom && !query.equalsIgnoreCase("all") && !query.equalsIgnoreCase("popular") &&
                (source.equalsIgnoreCase("auto") || source.equalsIgnoreCase("coomer")
                        || source.equalsIgnoreCase("party"))) {
            // Try Coomer first (Creator Search)
            List<Creator> coomerCreators = getCreatorsCached(COOMER_API, false);
            Creator coomerCreator = searchCreatorSmart(coomerCreators, query, null);

            if (coomerCreator == null) {
                // Coomer not found, try Kemono
                logger.info("🔄 Creator not found in Coomer, trying Kemono fallback...");
                List<Creator> kemonoCreators = getCreatorsCached(KEMONO_API, true);
                Creator kemonoCreator = searchCreatorSmart(kemonoCreators, query, null);

                if (kemonoCreator != null) {
                    isKemono = true;
                    logger.info("✅ Found in Kemono: " + kemonoCreator.name());
                }
            } else {
                logger.info("✅ Found in Coomer: " + coomerCreator.name());
            }
        }

        String apiUrl = isKemono ? KEMONO_API : COOMER_API;

        task.setStatus(QueueTask.Status.RUNNING);
        task.setTotalItems(targetPostCount);

        logger.info("🚀 Starting PartySource: " + query + " (Target: " + targetPostCount + ")");

        if (isRandom) {
            handleRandomSearch(task, apiUrl, targetPostCount, isKemono);
        } else if (query.equalsIgnoreCase("popular") || query.equalsIgnoreCase("all")) {
            handlePopularSearch(task, apiUrl, targetPostCount, isKemono);
        } else {
            // Exact match or fallback
            handleCreatorSearch(task, apiUrl, query, targetPostCount, isKemono);
        }

        logger.info("✅ PartySource finished: " + query);
    }

    private void handleRandomSearch(QueueTask task, String baseUrl, int targetPostCount, boolean isKemono) {
        try {
            // Use random offset to simulate "Random" posts
            // Posts are usually in the millions, but let's stick to a safe range
            int maxOffset = 5000;
            int rawOffset = new Random().nextInt(maxOffset);
            // ENFORCE STEPPING OF 50 (Crucial for API)
            int offset = (rawOffset / 50) * 50;

            logger.info("🎲 Fetching random posts from offset: " + offset);

            String json = fetch(baseUrl + "/posts?o=" + offset, isKemono);
            if (json == null)
                return;
            List<Post> posts = parsePostList(json);

            int queued = 0;
            // Shuffle posts to be truly random within the page
            Collections.shuffle(posts);

            for (Post post : posts) {
                if (queued >= targetPostCount)
                    break;
                Creator tempCreator = new Creator(post.user(), post.user(), post.service());
                boolean has = false;

                if (post.file() != null && processFile(post.file(), post, tempCreator, task, isKemono))
                    has = true;

                if (post.attachments() != null) {
                    for (FileInfo att : post.attachments()) {
                        if (processFile(att, post, tempCreator, task, isKemono))
                            has = true;
                    }
                }
                if (has)
                    queued++;
                task.setTotalItems(Math.max(queued, targetPostCount));
            }
        } catch (Exception e) {
            logger.error("RandomSearch Error", e);
        }
    }

    private void handlePopularSearch(QueueTask task, String baseUrl, int targetPostCount, boolean isKemono) {
        try {
            // API requires 'date' and 'period'
            // Format: YYYY-MM-DD
            String date = java.time.LocalDate.now().toString();
            String period = "week"; // Default to weekly popularity

            String url = String.format("%s/posts/popular?period=%s&date=%s", baseUrl, period, date);

            logger.info("🔥 Fetching Popular Posts: " + url);
            String json = fetch(url, isKemono);

            if (json == null) {
                logger.warn("❌ Popular fetch returned NULL");
                return;
            }

            // Debug: Check what we got
            logger.info("📄 Popular JSON Response length: " + json.length());
            if (json.length() < 500)
                logger.info("📄 Content: " + json);

            // Popular API returns { posts: [...] } (Actual response)
            // Docs said { results: [...] } but reality matches "posts"
            List<Post> posts = new ArrayList<>();
            try {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("posts")) {
                    JsonArray results = root.getAsJsonArray("posts");
                    logger.info("🔢 Found " + results.size() + " popular posts in JSON");
                    posts = gson.fromJson(results, new TypeToken<List<Post>>() {
                    }.getType());
                } else if (root.has("results")) {
                    // Fallback to 'results' just in case
                    JsonArray results = root.getAsJsonArray("results");
                    logger.info("🔢 Found " + results.size() + " popular posts in JSON (variant)");
                    posts = gson.fromJson(results, new TypeToken<List<Post>>() {
                    }.getType());
                } else {
                    logger.warn("⚠️ JSON does not contain 'posts' or 'results' field! Keys: " + root.keySet());
                }
            } catch (Exception e) {
                logger.error("Error parsing popular posts JSON", e);
            }

            int queued = 0;
            for (Post post : posts) {
                if (queued >= targetPostCount)
                    break;
                Creator tempCreator = new Creator(post.user(), post.user(), post.service());
                boolean has = false;

                if (post.file() != null && processFile(post.file(), post, tempCreator, task, isKemono))
                    has = true;

                if (post.attachments() != null) {
                    for (FileInfo att : post.attachments()) {
                        if (processFile(att, post, tempCreator, task, isKemono))
                            has = true;
                    }
                }
                if (has)
                    queued++;
                task.setTotalItems(Math.max(queued, targetPostCount));
            }
        } catch (Exception e) {
            logger.error("PopSearch Error", e);
        }
    }

    /**
     * Check if a creator exists in the specified source (coomer or kemono)
     * Used by download-cmd-plugin for fallback logic
     */
    public boolean creatorExists(String query, String source) {
        boolean isKemono = source.equalsIgnoreCase("kemono");
        String baseUrl = isKemono ? "https://kemono.su/api/v1" : "https://coomer.su/api/v1";

        try {
            List<Creator> allCreators = getCreatorsCached(baseUrl, isKemono);
            Creator creator = searchCreatorSmart(allCreators, query, null);
            return creator != null;
        } catch (Exception e) {
            logger.warn("Error checking creator existence: {}", e.getMessage());
            return false;
        }
    }

    private void handleCreatorSearch(QueueTask task, String baseUrl, String query, int targetPostCount,
            boolean isKemono) {
        try {
            // OPTIMIZED: Liste nur laden, wenn Cache leer oder alt
            List<Creator> allCreators = getCreatorsCached(baseUrl, isKemono);

            Creator creator = searchCreatorSmart(allCreators, query, null);

            if (creator == null) {
                logger.warn("Creator not found in list, trying direct ID lookup: " + query);
                creator = new Creator(query, query, isKemono ? "patreon" : "onlyfans");
            } else {
                logger.info("Found Creator: {} ({}) Service: {}", creator.name(), creator.id(), creator.service());
            }

            int filesQueued = 0;
            int offset = 0;

            while (filesQueued < targetPostCount && offset < 500) {
                logger.info("Fetching posts (Offset " + offset + ")...");
                List<Post> posts = fetchPostsPage(baseUrl, creator, offset, isKemono);

                if (posts.isEmpty()) {
                    logger.info("No more posts found.");
                    break;
                }
                logger.info("Found " + posts.size() + " posts.");

                for (Post post : posts) {
                    if (filesQueued >= targetPostCount)
                        break;

                    if (post.attachments() != null) {
                        for (FileInfo att : post.attachments()) {
                            if (processFile(att, post, creator, task, isKemono))
                                filesQueued++;
                        }
                    }
                    if (post.file() != null && processFile(post.file(), post, creator, task, isKemono))
                        filesQueued++;
                }
                offset += 50;
                task.setTotalItems(Math.max(filesQueued + 1, targetPostCount));
            }
            task.setTotalItems(filesQueued);

        } catch (Exception e) {
            logger.error("CreatorSearch Error", e);
        }
    }

    private boolean processFile(FileInfo info, Post post, Creator creator, QueueTask task, boolean isKemono) {
        if (info.path() == null)
            return false;

        // FILTER: Check filetype if specified (vid/img)
        String filterType = task.getString("filetype");
        if (filterType != null) {
            String ext = info.name().toLowerCase();
            boolean isVideo = ext.endsWith(".mp4") || ext.endsWith(".m4v") || ext.endsWith(".mov")
                    || ext.endsWith(".mkv") || ext.endsWith(".webm");
            boolean isImage = ext.endsWith(".jpg") || ext.endsWith(".jpeg") || ext.endsWith(".png")
                    || ext.endsWith(".gif") || ext.endsWith(".webp");

            if (filterType.equalsIgnoreCase("vid") && !isVideo) {
                return false;
            }
            if (filterType.equalsIgnoreCase("img") && !isImage) {
                return false;
            }
        }

        String historyKey = (creator.name() != null ? creator.name() : creator.id());
        if (kernel.getHistoryManager().isProcessed(historyKey, info.name()))
            return false;

        String safeTitle = (post.title() != null ? post.title() : post.id()).replaceAll("[^a-zA-Z0-9.-]", "_");
        if (safeTitle.length() > 50)
            safeTitle = safeTitle.substring(0, 50);

        String fileName = safeTitle + "_" + info.name();

        String url;
        if (info.path().startsWith("http")) {
            // Fall 1: Absolute URL (Alte Domains fixen)
            url = info.path();
            if (isKemono) {
                url = url.replace(".party", ".cr").replace(".su", ".cr");
            } else {
                url = url.replace(".party", ".st").replace(".su", ".st");
            }
        } else {
            // Fall 2: Relative URL (Muss /data enthalten)
            String path = info.path();
            if (!path.startsWith("/"))
                path = "/" + path;

            // FIX: Pfad muss mit /data beginnen, sonst 404
            if (!path.startsWith("/data")) {
                path = "/data" + path;
            }

            String cdn = isKemono ? KEMONO_CDN : COOMER_CDN;
            url = cdn + path;
        }

        PipelineItem item = new PipelineItem(url, fileName, task);
        item.getMetadata().put("creator", historyKey);
        item.getMetadata().put("source", isKemono ? "kemono" : "coomer");
        item.getMetadata().put("service", post.service());
        item.getMetadata().put("post_date", post.published());
        item.getMetadata().put("raw_id", info.name());

        // --- HEADERS ---
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", isKemono ? "https://kemono.cr/" : "https://coomer.st/");
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "text/css, */*");

        item.getMetadata().put("headers", headers);

        kernel.getPipelineManager().submit(item);
        kernel.getHistoryManager().markAsProcessed(historyKey, info.name());
        return true;
    }

    // --- Helpers ---

    // CACHING LOGIC
    private synchronized List<Creator> getCreatorsCached(String baseUrl, boolean isKemono) {
        long now = System.currentTimeMillis();
        if (isKemono) {
            if (cacheKemono != null && (now - timeKemono < CACHE_LIFETIME)) {
                logger.info("Using cached Kemono creators list.");
                return cacheKemono;
            }
        } else {
            if (cacheCoomer != null && (now - timeCoomer < CACHE_LIFETIME)) {
                logger.info("Using cached Coomer creators list.");
                return cacheCoomer;
            }
        }

        // Fetch new
        logger.info("Downloading fresh creators list...");
        List<Creator> list = fetchCreators(baseUrl, isKemono);

        // Save to cache
        if (isKemono) {
            cacheKemono = list;
            timeKemono = now;
        } else {
            cacheCoomer = list;
            timeCoomer = now;
        }
        return list;
    }

    private List<Post> fetchPostsPage(String baseUrl, Creator c, int offset, boolean isKemono) {
        try {
            String u = String.format("%s/%s/user/%s/posts?o=%d", baseUrl, c.service(), c.id(), offset);
            return parsePostList(fetch(u, isKemono));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Creator> fetchCreators(String baseUrl, boolean isKemono) {
        try {
            return gson.fromJson(fetch(baseUrl + "/creators", isKemono), new TypeToken<List<Creator>>() {
            }.getType());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Creator searchCreatorSmart(List<Creator> all, String q, String s) {
        if (all == null || q == null)
            return null;

        String query = q.trim();
        String qLower = query.toLowerCase();

        // 1. PRIORITY: Exact Match (Name OR ID)
        for (Creator c : all) {
            if (c.name().trim().equalsIgnoreCase(query))
                return c;
            if (c.id().trim().equalsIgnoreCase(query))
                return c;
        }

        // 2. PRIORITY: Starts With (Name)
        for (Creator c : all) {
            if (c.name().toLowerCase().trim().startsWith(qLower))
                return c;
        }

        // 3. PRIORITY: Contains (Sorted by Shortest Name)
        List<Creator> matches = new ArrayList<>();
        for (Creator c : all) {
            if (c.name().toLowerCase().contains(qLower))
                matches.add(c);
        }

        if (!matches.isEmpty()) {
            matches.sort(Comparator.comparingInt(c -> c.name().length()));
            return matches.get(0);
        }

        return null;
    }

    private List<Post> parsePostList(String json) {
        try {
            JsonElement r = JsonParser.parseString(json);
            if (r.isJsonArray())
                return gson.fromJson(r, new TypeToken<List<Post>>() {
                }.getType());
        } catch (Exception e) {
        }
        return new ArrayList<>();
    }

    private String fetch(String u, boolean isKemono) throws Exception {
        URL url = new URL(u);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);

        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "text/css");
        c.setRequestProperty("Referer", isKemono ? "https://kemono.cr/" : "https://coomer.st/");

        InputStream i = c.getInputStream();
        if ("gzip".equals(c.getContentEncoding()))
            i = new GZIPInputStream(i);
        return new String(i.readAllBytes(), StandardCharsets.UTF_8);
    }
}