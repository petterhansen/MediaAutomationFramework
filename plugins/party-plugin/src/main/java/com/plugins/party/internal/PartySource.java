package internal;

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

    public record Creator(String id, String name, String service) {}
    public record Post(String id, String service, String user, String title, String content, FileInfo file, List<FileInfo> attachments, String published) {}
    public record FileInfo(String name, String path) {}

    public PartySource(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public String getName() { return "PartySource"; }

    @Override
    public void execute(QueueTask task) {
        String query = task.getString("query");
        if (query == null) return;

        int targetPostCount = task.getInt("amount", 1);
        String source = task.getString("source");
        boolean isKemono = "kemono".equalsIgnoreCase(source);

        String apiUrl = isKemono ? KEMONO_API : COOMER_API;

        task.setStatus(QueueTask.Status.RUNNING);
        task.setTotalItems(targetPostCount);

        logger.info("🚀 Starting PartySource: " + query + " (Target: " + targetPostCount + ")");

        if (query.equalsIgnoreCase("all")) {
            handlePopularSearch(task, apiUrl, targetPostCount, isKemono);
        } else {
            handleCreatorSearch(task, apiUrl, query, targetPostCount, isKemono);
        }

        logger.info("✅ PartySource finished: " + query);
    }

    private void handlePopularSearch(QueueTask task, String baseUrl, int targetPostCount, boolean isKemono) {
        try {
            String json = fetch(baseUrl + "/posts/popular", isKemono);
            if (json == null) return;
            List<Post> posts = parsePostList(json);

            int queued = 0;
            for (Post post : posts) {
                if (queued >= targetPostCount) break;
                Creator tempCreator = new Creator(post.user(), post.user(), post.service());
                boolean has = false;

                if (post.file() != null && processFile(post.file(), post, tempCreator, task, isKemono)) has=true;

                if (post.attachments() != null) {
                    for (FileInfo att : post.attachments()) {
                        if (processFile(att, post, tempCreator, task, isKemono)) has=true;
                    }
                }
                if(has) queued++;
                task.setTotalItems(Math.max(queued, targetPostCount));
            }
        } catch (Exception e) { logger.error("PopSearch Error", e); }
    }

    private void handleCreatorSearch(QueueTask task, String baseUrl, String query, int targetPostCount, boolean isKemono) {
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
                    if (filesQueued >= targetPostCount) break;

                    if (post.attachments() != null) {
                        for (FileInfo att : post.attachments()) {
                            if (processFile(att, post, creator, task, isKemono)) filesQueued++;
                        }
                    }
                    if (post.file() != null && processFile(post.file(), post, creator, task, isKemono)) filesQueued++;
                }
                offset += 50;
                task.setTotalItems(Math.max(filesQueued + 1, targetPostCount));
            }
            task.setTotalItems(filesQueued);

        } catch (Exception e) { logger.error("CreatorSearch Error", e); }
    }

    private boolean processFile(FileInfo info, Post post, Creator creator, QueueTask task, boolean isKemono) {
        if (info.path() == null) return false;

        String historyKey = (creator.name() != null ? creator.name() : creator.id());
        if (kernel.getHistoryManager().isProcessed(historyKey, info.name())) return false;

        String safeTitle = (post.title() != null ? post.title() : post.id()).replaceAll("[^a-zA-Z0-9.-]", "_");
        if (safeTitle.length() > 50) safeTitle = safeTitle.substring(0, 50);

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
            if (!path.startsWith("/")) path = "/" + path;

            // FIX: Pfad muss mit /data beginnen, sonst 404
            if (!path.startsWith("/data")) {
                path = "/data" + path;
            }

            String cdn = isKemono ? KEMONO_CDN : COOMER_CDN;
            url = cdn + path;
        }

        PipelineItem item = new PipelineItem(url, fileName, task);
        item.getMetadata().put("creator", historyKey);
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
        } catch(Exception e){ return Collections.emptyList(); }
    }

    private List<Creator> fetchCreators(String baseUrl, boolean isKemono) {
        try { return gson.fromJson(fetch(baseUrl + "/creators", isKemono), new TypeToken<List<Creator>>(){}.getType()); } catch(Exception e){ return new ArrayList<>(); }
    }

    private Creator searchCreatorSmart(List<Creator> all, String q, String s) {
        if(all==null)return null;
        for(Creator c:all) if(c.name().equalsIgnoreCase(q) || c.id().equalsIgnoreCase(q)) return c;
        return null;
    }

    private List<Post> parsePostList(String json) {
        try { JsonElement r=JsonParser.parseString(json); if(r.isJsonArray()) return gson.fromJson(r, new TypeToken<List<Post>>(){}.getType()); } catch(Exception e){} return new ArrayList<>();
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
        if("gzip".equals(c.getContentEncoding())) i = new GZIPInputStream(i);
        return new String(i.readAllBytes(), StandardCharsets.UTF_8);
    }
}