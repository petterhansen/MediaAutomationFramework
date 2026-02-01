package com.framework.modules.source;

import com.framework.api.MediaSource;
import com.framework.common.model.GenericMediaItem;
import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verarbeitet "Browser Dump" Tasks.
 * Reicht Cookies und Referer an den Downloader weiter.
 */
public class BrowserSource implements MediaSource {
    private static final Logger logger = LoggerFactory.getLogger(BrowserSource.class);
    private final Kernel kernel;

    public BrowserSource(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void execute(QueueTask task) {
        List<String> urls = task.getStringList("urls");
        String folder = task.getString("folder");
        String cookies = task.getString("cookies"); // Cookies vom Browser-Push
        String referer = task.getString("referer"); // Optional: Referer oft wichtig für Hotlink-Schutz

        logger.info("Processing Browser Batch: {} items into folder '{}'", urls.size(), folder);

        for (String url : urls) {
            String fileName = extractFilename(url);

            // Generic Item erstellen (dient hier eher als Datenhalter)
            GenericMediaItem item = new GenericMediaItem(
                    null,
                    "browser_push",
                    "web",
                    folder,
                    fileName,
                    url,
                    null,
                    ""
            );

            // PipelineItem erstellen
            PipelineItem pipeItem = new PipelineItem(item.fileUrl(), item.originalFileName(), task);

            // --- NEU: Header Injection ---
            if ((cookies != null && !cookies.isEmpty()) || (referer != null && !referer.isEmpty())) {
                Map<String, String> headers = new HashMap<>();

                if (cookies != null && !cookies.isEmpty()) {
                    headers.put("Cookie", cookies);
                }

                if (referer != null && !referer.isEmpty()) {
                    headers.put("Referer", referer);
                } else {
                    // Fallback: Die URL selbst als Referer nutzen (hilft oft bei SimpCity/Coomer)
                    headers.put("Referer", "https://simpcity.su/");
                }

                // User-Agent ist auch oft nützlich, falls im Task vorhanden, sonst Standard
                headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

                // Metadaten setzen, die der Kernel im DownloadHandler ausliest
                pipeItem.getMetadata().put("headers", headers);
            }
            // -----------------------------

            kernel.getPipelineManager().submit(pipeItem);
        }
    }

    private String extractFilename(String url) {
        if (url == null) return "unknown_" + System.currentTimeMillis();
        String f = url.substring(url.lastIndexOf('/') + 1);
        if (f.contains("?")) f = f.substring(0, f.indexOf("?"));
        // Decode URL encoding if necessary, basic cleanup
        return f;
    }

    @Override
    public String getName() {
        return "BrowserSource";
    }
}