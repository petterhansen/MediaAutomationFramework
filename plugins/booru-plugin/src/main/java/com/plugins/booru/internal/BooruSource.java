package internal;

import com.framework.api.MediaSource;
import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BooruSource implements MediaSource {
    private static final Logger logger = LoggerFactory.getLogger(BooruSource.class);
    private final Kernel kernel;

    public record BooruConfig(String name, String apiUrl, String refererUrl, String userId, String apiKey) {}
    public record BooruPost(String fileUrl, String tags) {}

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gecko/20100101 Firefox/91.0";
    private static final Map<String, BooruConfig> CONFIGS = Map.of(
            "r34", new BooruConfig("Rule34", "https://api.rule34.xxx/index.php?page=dapi&s=post&q=index", "https://rule34.xxx/", "0", ""),
            "xb",  new BooruConfig("XBooru", "https://xbooru.com/index.php?page=dapi&s=post&q=index", "https://xbooru.com/", "0", ""),
            "sb",  new BooruConfig("Safebooru", "https://safebooru.org/index.php?page=dapi&s=post&q=index", "https://safebooru.org/", "0", "")
    );

    public BooruSource(Kernel kernel) { this.kernel = kernel; }

    @Override
    public void execute(QueueTask task) {
        String query = task.getString("query");
        int amount = task.getInt("amount", 1);
        String sourceKey = task.getString("source");
        BooruConfig config = CONFIGS.get(sourceKey);
        if (config == null) return;

        boolean isRandom = (query == null || query.trim().isEmpty() || query.equalsIgnoreCase("all"));

        // UPDATE: Status und Total setzen
        task.setStatus(QueueTask.Status.RUNNING);
        task.setTotalItems(amount);

        try {
            StringBuilder url = new StringBuilder(config.apiUrl());
            if (!config.userId().equals("0")) url.append("&user_id=").append(config.userId()).append("&api_key=").append(config.apiKey());

            if (!isRandom) url.append("&tags=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));

            int pid = 0;
            if (isRandom) {
                // Random Page Logic (simplified)
                pid = new Random().nextInt(1000);
            }

            int limit = isRandom ? 100 : amount;
            url.append("&limit=").append(limit).append("&pid=").append(pid);

            Document doc = fetchXml(url.toString(), config.refererUrl());
            if (doc == null) return;

            NodeList posts = doc.getElementsByTagName("post");
            int queued = 0;

            for(int i=0; i<posts.getLength(); i++) {
                if (queued >= amount) break;
                Element el = (Element)posts.item(i);
                String rawUrl = el.getAttribute("file_url");
                if(rawUrl.startsWith("//")) rawUrl = "https:"+rawUrl;

                String fName = rawUrl.substring(rawUrl.lastIndexOf('/')+1);
                if(kernel.getHistoryManager().isProcessed(sourceKey, fName)) continue;

                PipelineItem item = new PipelineItem(rawUrl, fName, task);
                item.getMetadata().put("creator", sourceKey);
                item.getMetadata().put("tags", el.getAttribute("tags"));
                if(!config.refererUrl().isEmpty()) item.getMetadata().put("headers", Map.of("Referer", config.refererUrl()));

                kernel.getPipelineManager().submit(item);
                kernel.getHistoryManager().markAsProcessed(sourceKey, fName);
                queued++;
            }

            // Korrigiere Total am Ende (falls weniger gefunden wurden)
            task.setTotalItems(queued);

        } catch (Exception e) { logger.error("Booru Error", e); }
    }

    private Document fetchXml(String u, String r) {
        try {
            HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
            c.setRequestProperty("User-Agent", USER_AGENT);
            if(r!=null && !r.isEmpty()) c.setRequestProperty("Referer", r);
            if(c.getResponseCode()!=200)return null;
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(c.getInputStream());
        } catch(Exception e){return null;}
    }

    @Override public String getName(){ return "BooruSource"; }
}