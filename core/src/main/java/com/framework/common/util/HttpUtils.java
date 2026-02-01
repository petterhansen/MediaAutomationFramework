package com.framework.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class HttpUtils {
    private static final Logger logger = LoggerFactory.getLogger(HttpUtils.class);
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MediaAutomationFramework/1.0";

    public static String get(String urlStr, Map<String, String> headers) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
            if (headers != null) headers.forEach(conn::setRequestProperty);

            return readResponse(conn);
        } catch (Exception e) {
            logger.error("HTTP GET failed: {}", e.getMessage());
            return null;
        }
    }

    public static String postJson(String urlStr, String jsonBody) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            return readResponse(conn);
        } catch (Exception e) {
            logger.error("HTTP POST JSON failed: {}", e.getMessage());
            return null;
        }
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return null;

        if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
            in = new GZIPInputStream(in);
        }

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}