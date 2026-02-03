package com.plugins.telegram.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Dedicated uploader class that replicates the exact behavior of the old
 * MediaAutomationFramework TelegramClient to ensure compatibility with mobile
 * clients.
 */
public class TelegramUploader {
    private static final Logger logger = LoggerFactory.getLogger(TelegramUploader.class);
    private final String botToken;
    private final String apiBase;

    public TelegramUploader(String botToken, String apiBase) {
        this.botToken = botToken;
        this.apiBase = apiBase;
    }

    public record MediaItem(File file, File thumbnail, int width, int height, int duration, boolean isVideo) {
    }

    public void uploadMediaGroup(List<MediaItem> items, String caption, String chatId, Integer threadId)
            throws IOException {
        logger.info("🚀 Starting MediaGroup Upload ({} items)", items.size());
        String boundary = "---" + UUID.randomUUID();
        String method = "sendMediaGroup";
        URL url = new URL(String.format(apiBase, botToken, method));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setChunkedStreamingMode(64 * 1024);

        try (OutputStream output = conn.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)) {

            addFormField(writer, boundary, "chat_id", chatId);
            if (threadId != null)
                addFormField(writer, boundary, "message_thread_id", String.valueOf(threadId));

            JsonArray mediaArr = new JsonArray();

            for (int i = 0; i < items.size(); i++) {
                MediaItem item = items.get(i);
                JsonObject obj = new JsonObject();
                obj.addProperty("type", item.isVideo() ? "video" : "photo");
                obj.addProperty("media", "attach://file" + i);

                if (i == 0 && caption != null) {
                    obj.addProperty("caption", caption);
                }

                if (item.isVideo() && item.thumbnail() != null) {
                    obj.addProperty("thumbnail", "attach://thumb" + i);
                    if (item.width() > 0)
                        obj.addProperty("width", item.width());
                    if (item.height() > 0)
                        obj.addProperty("height", item.height());
                    if (item.duration() > 0)
                        obj.addProperty("duration", item.duration());
                }

                mediaArr.add(obj);
            }

            addFormField(writer, boundary, "media", mediaArr.toString());

            // Attach Files
            for (int i = 0; i < items.size(); i++) {
                MediaItem item = items.get(i);
                logger.debug("📤 Attaching file{}: {}", i, item.file().getName());
                attachFile(writer, output, boundary, "file" + i, item.file());

                if (item.isVideo() && item.thumbnail() != null) {
                    logger.debug("   + Attaching thumb{}: {}", i, item.thumbnail().getName());
                    attachFile(writer, output, boundary, "thumb" + i, item.thumbnail());
                }
            }

            writer.append("--").append(boundary).append("--\r\n").flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readError(conn);
            logger.error("❌ MediaGroup Upload Failed ({}): {}", code, err);
            throw new IOException("Telegram API Error " + code + ": " + err);
        } else {
            logger.info("✅ MediaGroup Upload Successful!");
        }
    }

    public void uploadVideo(File videoFile, File thumbnail, String caption, String chatId, Integer threadId,
            int width, int height, int duration) throws IOException {

        logger.info("🚀 Starting Video Upload for: {}", videoFile.getName());
        logger.debug("   > Path: {}", videoFile.getAbsolutePath());
        logger.debug("   > Size: {} bytes", videoFile.length());
        if (thumbnail != null) {
            logger.debug("   > Thumbnail: {} ({} bytes)", thumbnail.getAbsolutePath(), thumbnail.length());
        } else {
            logger.debug("   > Thumbnail: NONE");
        }
        logger.debug("   > Metadata: {}x{}, {}s", width, height, duration);

        String boundary = "---" + UUID.randomUUID();
        String method = "sendVideo";
        URL url = new URL(String.format(apiBase, botToken, method));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");

        // CRITICAL: Connection settings from old implementation
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(30 * 1000);
        conn.setReadTimeout(60 * 60 * 1000);
        conn.setChunkedStreamingMode(64 * 1024); // 64KB chunks

        try (OutputStream output = conn.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)) {

            // 1. Chat ID
            addFormField(writer, boundary, "chat_id", chatId);

            // 2. Thread ID
            if (threadId != null) {
                addFormField(writer, boundary, "message_thread_id", String.valueOf(threadId));
            }

            // 3. Caption (Before metadata/file in old impl)
            if (caption != null) {
                addFormField(writer, boundary, "caption", caption);
            }

            // 4. Metadata
            if (width > 0)
                addFormField(writer, boundary, "width", String.valueOf(width));
            if (height > 0)
                addFormField(writer, boundary, "height", String.valueOf(height));
            if (duration > 0)
                addFormField(writer, boundary, "duration", String.valueOf(duration));

            // 5. Video File (MUST BE BEFORE THUMBNAIL)
            logger.debug("📤 Attaching video file stream...");
            attachFile(writer, output, boundary, "video", videoFile);

            // 6. Thumbnail
            if (thumbnail != null && thumbnail.exists()) {
                logger.debug("📤 Attaching thumbnail stream...");
                attachFile(writer, output, boundary, "thumb", thumbnail);
            }

            writer.append("--").append(boundary).append("--\r\n").flush();
            logger.debug("✅ Request body complete. Waiting for response...");
        }

        int code = conn.getResponseCode();
        logger.debug("📥 Response Code: {}", code);

        if (code != 200) {
            String err = readError(conn);
            logger.error("❌ Upload Failed ({}): {}", code, err);
            throw new IOException("Telegram API Error " + code + ": " + err);
        } else {
            logger.info("✅ Upload Successful!");
        }
    }

    public void uploadDocument(File file, String caption, String chatId, Integer threadId, File thumbnail)
            throws IOException {
        logger.info("🚀 Starting Document Upload for: {}", file.getName());
        String boundary = "---" + UUID.randomUUID();
        String method = "sendDocument";
        URL url = new URL(String.format(apiBase, botToken, method));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setChunkedStreamingMode(64 * 1024);

        try (OutputStream output = conn.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)) {

            addFormField(writer, boundary, "chat_id", chatId);
            if (threadId != null)
                addFormField(writer, boundary, "message_thread_id", String.valueOf(threadId));
            if (caption != null)
                addFormField(writer, boundary, "caption", caption);

            logger.debug("📤 Attaching document file stream...");
            attachFile(writer, output, boundary, "document", file);

            if (thumbnail != null && thumbnail.exists()) {
                logger.debug("📤 Attaching thumbnail stream...");
                attachFile(writer, output, boundary, "thumb", thumbnail);
            }

            writer.append("--").append(boundary).append("--\r\n").flush();
        }

        if (conn.getResponseCode() != 200) {
            throw new IOException("API Error " + conn.getResponseCode() + ": " + readError(conn));
        }
        logger.info("✅ Document Upload Successful!");
    }

    public void uploadPhoto(File file, String caption, String chatId, Integer threadId) throws IOException {
        logger.info("🚀 Starting Photo Upload for: {}", file.getName());
        String boundary = "---" + UUID.randomUUID();
        String method = "sendPhoto";
        URL url = new URL(String.format(apiBase, botToken, method));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setChunkedStreamingMode(64 * 1024);

        try (OutputStream output = conn.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)) {

            addFormField(writer, boundary, "chat_id", chatId);
            if (threadId != null)
                addFormField(writer, boundary, "message_thread_id", String.valueOf(threadId));
            if (caption != null)
                addFormField(writer, boundary, "caption", caption);

            attachFile(writer, output, boundary, "photo", file);
            writer.append("--").append(boundary).append("--\r\n").flush();
        }

        if (conn.getResponseCode() != 200) {
            throw new IOException("API Error " + conn.getResponseCode() + ": " + readError(conn));
        }
        logger.info("✅ Photo Upload Successful!");
    }

    // --- Helpers ---

    private void addFormField(PrintWriter writer, String boundary, String name, String value) {
        logger.trace("   Field: {} = {}", name, value);
        writer.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    private void attachFile(PrintWriter writer, OutputStream output, String boundary, String name, File file)
            throws IOException {
        logger.trace("   Attaching file field '{}': {}", name, file.getName());
        writer.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"; filename=\"")
                .append(file.getName()).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n")
                .flush();

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; // 64KB buffer
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            output.flush();
        }
        writer.append("\r\n").flush();
    }

    private String readError(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            if (es == null)
                return "No error stream";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(es))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null)
                    sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            return "Failed to read error: " + e.getMessage();
        }
    }
}
