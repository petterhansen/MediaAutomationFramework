package com.plugins.dashboard.internal;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class AdBlocker {

    // A simple list of common ad/tracking domains to completely remove
    private static final String[] BLOCKED_DOMAINS = {
            "googleads", "doubleclick.net", "adsystem", "adnxs",
            "criteo", "taboola", "outbrain", "adform", "pubmatic",
            "rubiconproject", "amazon-adsystem", "googlesyndication",
            "analytics", "tracker", "popads"
    };

    /**
     * Sanitizes the HTML content by removing ads, trackers, and popup scripts.
     * Also rewrites relative URLs to absolute based on the base URL,
     * and injects a script to intercept link clicks to route them through the proxy.
     */
    public static String sanitizeHtml(String html, String baseUrl, String cookies) {
        Document doc = Jsoup.parse(html, baseUrl);

        // 1. Remove script tags that match blocked domains or contain suspicious inline code
        Elements scripts = doc.select("script");
        for (Element script : scripts) {
            String src = script.attr("src").toLowerCase();
            if (isBlocked(src)) {
                script.remove();
                continue;
            }
            // Remove common popup/popunder inline scripts
            String htmlContent = script.html().toLowerCase();
            if (htmlContent.contains("window.open") || htmlContent.contains("popunder")) {
                script.remove();
            }
        }

        // 2. Remove iframe ads
        Elements iframes = doc.select("iframe");
        for (Element iframe : iframes) {
            String src = iframe.attr("src").toLowerCase();
            if (isBlocked(src) || src.contains("ad")) {
                iframe.remove();
            }
        }

        // 3. Remove common ad containers by class/id
        doc.select(".ad, .ads, .advertisement, .banner-ad, #ad, #ads").remove();

        // 4. Ensure all links are absolute
        Elements links = doc.select("a[href]");
        for (Element link : links) {
            String absUrl = link.absUrl("href");
            if (!absUrl.isEmpty() && !absUrl.startsWith("javascript:") && !absUrl.startsWith("mailto:")) {
                link.attr("href", absUrl);
            }
        }

        // Deep Proxy all images and media tags so Cloudflare cookies apply to them too
        Elements images = doc.select("img[src]");
        for (Element img : images) {
            String absUrl = img.absUrl("src");
            if (!absUrl.isEmpty() && !absUrl.startsWith("data:") && !absUrl.startsWith("blob:")) {
                String proxyUrl = "/api/proxy?url=" + java.net.URLEncoder.encode(absUrl, java.nio.charset.StandardCharsets.UTF_8);
                if (cookies != null && !cookies.isEmpty()) {
                    proxyUrl += "&cookies=" + java.net.URLEncoder.encode(cookies, java.nio.charset.StandardCharsets.UTF_8);
                }
                img.attr("src", proxyUrl);
            }
        }

        Elements media = doc.select("video[src], source[src], audio[src]");
        for (Element m : media) {
            String absUrl = m.absUrl("src");
            if (!absUrl.isEmpty() && !absUrl.startsWith("data:") && !absUrl.startsWith("blob:")) {
                String proxyUrl = "/api/proxy?url=" + java.net.URLEncoder.encode(absUrl, java.nio.charset.StandardCharsets.UTF_8);
                if (cookies != null && !cookies.isEmpty()) {
                    proxyUrl += "&cookies=" + java.net.URLEncoder.encode(cookies, java.nio.charset.StandardCharsets.UTF_8);
                }
                m.attr("src", proxyUrl);
            }
        }

        // 5. Inject client-side interception script
        // This script forces all link clicks to go through our proxy
        Element head = doc.head();
        if (head != null) {
            head.append(
                "<script>\n" +
                "document.addEventListener('DOMContentLoaded', () => {\n" +
                "    document.body.addEventListener('click', (e) => {\n" +
                "        let target = e.target.closest('a');\n" +
                "        if (target && target.href && !target.href.startsWith('javascript:')) {\n" +
                "            e.preventDefault();\n" +
                "            window.parent.postMessage({ type: 'PROXY_NAVIGATE', url: target.href }, '*');\n" +
                "        }\n" +
                "    });\n" +
                "    \n" +
                "    const countMedia = () => {\n" +
                "        const extRegex = /\\.(mp4|webm|mkv|avi|mov|jpg|jpeg|png|gif|webp)(\\?|$)/i;\n" +
                "        let mediaCount = 0;\n" +
                "        document.querySelectorAll('video, img').forEach(el => {\n" +
                "            if (el.src && extRegex.test(el.src)) mediaCount++;\n" +
                "            else if (el.tagName === 'IMG' && el.src && !el.src.includes('data:image') && !el.src.includes('logo') && !el.src.includes('avatar')) mediaCount++;\n" +
                "        });\n" +
                "        document.querySelectorAll('source').forEach(el => {\n" +
                "            if (el.src && extRegex.test(el.src)) mediaCount++;\n" +
                "        });\n" +
                "        document.querySelectorAll('a').forEach(el => {\n" +
                "            if (el.href && extRegex.test(el.href)) mediaCount++;\n" +
                "        });\n" +
                "        window.parent.postMessage({ type: 'PROXY_MEDIA_COUNT', count: mediaCount }, '*');\n" +
                "    };\n" +
                "    countMedia();\n" +
                "    // Observe dynamic DOM changes (e.g., infinite scrolling)\n" +
                "    new MutationObserver(() => countMedia()).observe(document.body, { childList: true, subtree: true });\n" +
                "});\n" +
                "</script>"
            );
            
            // Inject base tag just in case
            head.prepend("<base href=\"" + baseUrl + "\">");
        }

        return doc.outerHtml();
    }

    private static boolean isBlocked(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            // Very basic matching
            for (String domain : BLOCKED_DOMAINS) {
                if (url.contains(domain)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
        return false;
    }
}
