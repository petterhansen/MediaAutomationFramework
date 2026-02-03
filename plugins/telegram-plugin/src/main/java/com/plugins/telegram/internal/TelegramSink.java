package com.plugins.telegram.internal;

import com.framework.api.MediaSink;
import com.framework.core.pipeline.PipelineItem;
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

public class TelegramSink implements MediaSink {
    private static final Logger logger = LoggerFactory.getLogger(TelegramSink.class);
    private final String botToken;
    private final String targetChatId;
    private final String apiBase;

    public TelegramSink(String botToken, String chatId, String apiUrlPattern) {
        this.botToken = botToken;
        this.targetChatId = chatId;
        this.apiBase = (apiUrlPattern != null ? apiUrlPattern : "http://localhost:8081/bot%s/%s");
    }

    @Override
    public Void process(PipelineItem item) throws Exception {
        List<File> files = item.getProcessedFiles();
        if (files == null || files.isEmpty())
            return null;

        // Determine target chat: use initiator if available, otherwise default config
        String actualTargetChatId = targetChatId;
        Integer actualThreadId = null;
        if (item.getParentTask() != null) {
            String initiator = item.getParentTask().getString("initiatorChatId");
            if (initiator != null && !initiator.isEmpty()) {
                actualTargetChatId = initiator;
                logger.debug("Routing upload to initiator: {}", actualTargetChatId);
            }

            // Topic support
            String threadIdStr = item.getParentTask().getString("initiatorThreadId");
            if (threadIdStr != null && !threadIdStr.isEmpty()) {
                try {
                    actualThreadId = Integer.parseInt(threadIdStr);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String caption = null;
        if (item.getParentTask() != null) {
            caption = item.getParentTask().getString("caption");
        }

        if (caption == null) {
            if (item.getMetadata().containsKey("creator")) {
                String creator = (String) item.getMetadata().get("creator");
                if (creator != null) {
                    String tag = creator.toLowerCase().trim().replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "");
                    caption = "#" + tag;
                }
            }
            if (caption == null)
                caption = "#" + item.getOriginalName();
        }

        // Album (MediaGroup) nur senden, wenn > 1 Datei UND <= 10
        if (files.size() > 1 && files.size() <= 10) {
            sendMediaGroup(files, caption, actualTargetChatId, actualThreadId);
        } else {
            // Einzeln senden
            for (File f : files) {
                uploadFile(f, caption, actualTargetChatId, actualThreadId);
                Thread.sleep(1000); // Rate Limit Schutz
            }
        }
        return null;
    }

    private void uploadFile(File file, String caption, String chatId, Integer threadId) {
        // Versuch 1: Als Foto oder Video senden
        try {
            String method;
            String field;

            if (isVideo(file)) {
                method = "sendVideo";
                field = "video";
            } else if (isImage(file)) {
                // FIX: Bilder explizit als Photo senden
                method = "sendPhoto";
                field = "photo";
            } else {
                method = "sendDocument";
                field = "document";
            }

            performMultipartUpload(method, field, file, caption, false, null, chatId, threadId);

        } catch (Exception e) {
            logger.warn("Standard upload failed for {}, trying fallback to document...", file.getName());
            // Fallback: Als Dokument senden, wenn sendPhoto/Video fehlschlägt
            try {
                performMultipartUpload("sendDocument", "document", file, caption, false, null, chatId, threadId);
            } catch (Exception ex) {
                logger.error("Final upload failed for {}", file.getName(), ex);
            }
        }
    }

    private void sendMediaGroup(List<File> files, String caption, String chatId, Integer threadId) {
        try {
            // Nur gültige Medien für Group filtern
            List<File> validFiles = files.stream().filter(f -> isVideo(f) || isImage(f)).toList();
            if (validFiles.isEmpty())
                return;

            if (validFiles.size() == 1) {
                uploadFile(validFiles.get(0), caption, chatId, threadId);
                return;
            }

            performMultipartUpload("sendMediaGroup", "media", null, caption, true, validFiles, chatId, threadId);
        } catch (Exception e) {
            logger.error("Group upload failed", e);
        }
    }

    private void performMultipartUpload(String method, String fileField, File singleFile, String caption,
            boolean isGroup, List<File> groupFiles, String chatId, Integer threadId) throws IOException {
        String boundary = "---" + UUID.randomUUID();
        URL url = new URL(String.format(apiBase, botToken, method));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream output = conn.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)) {

            addFormField(writer, boundary, "chat_id", chatId);
            if (threadId != null) {
                addFormField(writer, boundary, "message_thread_id", String.valueOf(threadId));
            }

            if (isGroup) {
                JsonArray mediaArr = new JsonArray();
                for (int i = 0; i < groupFiles.size(); i++) {
                    File f = groupFiles.get(i);
                    JsonObject obj = new JsonObject();
                    obj.addProperty("type", isVideo(f) ? "video" : "photo");
                    obj.addProperty("media", "attach://file" + i);
                    if (i == 0 && caption != null)
                        obj.addProperty("caption", caption);
                    mediaArr.add(obj);
                }
                addFormField(writer, boundary, "media", mediaArr.toString());
                for (int i = 0; i < groupFiles.size(); i++)
                    attachFile(writer, output, boundary, "file" + i, groupFiles.get(i));
            } else {
                if (caption != null)
                    addFormField(writer, boundary, "caption", caption);
                attachFile(writer, output, boundary, fileField, singleFile);
            }

            writer.append("--").append(boundary).append("--\r\n").flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readError(conn);
            logger.warn("TG Error {}: {} | {}", code, method, err);
            throw new IOException("API Error " + code); // Wirf Exception für Fallback
        } else {
            logger.info("TG Upload OK ({})", method);
        }
    }

    private void addFormField(PrintWriter writer, String boundary, String name, String value) {
        writer.append("--").append(boundary).append("\r\n").append("Content-Disposition: form-data; name=\"")
                .append(name).append("\"\r\n\r\n").append(value).append("\r\n");
    }

    private void attachFile(PrintWriter writer, OutputStream output, String boundary, String name, File file)
            throws IOException {
        writer.append("--").append(boundary).append("\r\n").append("Content-Disposition: form-data; name=\"")
                .append(name).append("\"; filename=\"").append(file.getName()).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n").flush();
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.transferTo(output);
        }
        writer.append("\r\n").flush();
    }

    private String readError(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            if (es == null)
                return "No error stream available";

            try (BufferedReader r = new BufferedReader(new InputStreamReader(es))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null)
                    sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            return "N/A";
        }
    }

    private boolean isVideo(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm");
    }

    private boolean isImage(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".bmp");
    }
}
