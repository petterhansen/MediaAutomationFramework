package com.plugins.telegram.internal;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Portierung von src/processing/ChannelSyncWorker.java
 * Erlaubt das systematische Kopieren (Forwarding) von Nachrichtenbereichen.
 */
public class ChannelSyncWorker {
    private static final Logger logger = LoggerFactory.getLogger(ChannelSyncWorker.class);
    private final String botToken;
    private final String targetChatId;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread workerThread;

    public ChannelSyncWorker(String botToken, String targetChatId) {
        this.botToken = botToken;
        this.targetChatId = targetChatId;
    }

    public void startSync(String sourceChatId, int startId, int endId, int delayMs) {
        if (workerThread != null && workerThread.isAlive()) {
            logger.warn("Sync already running!");
            return;
        }
        stopRequested.set(false);

        workerThread = new Thread(() -> {
            logger.info("=== SYNC START: {} -> {} (IDs: {}-{}) ===", sourceChatId, targetChatId, startId, endId);
            int success = 0;
            int failed = 0;

            for (int id = startId; id <= endId; id++) {
                if (stopRequested.get()) break;

                if (forwardMessage(sourceChatId, targetChatId, id)) {
                    success++;
                } else {
                    failed++;
                }

                if ((id - startId) % 20 == 0) {
                    logger.info("Sync Progress: ID {}/{} (OK: {}, Fail: {})", id, endId, success, failed);
                }

                try { Thread.sleep(delayMs); } catch (InterruptedException e) { break; }
            }
            logger.info("=== SYNC DONE ===");
        });
        workerThread.start();
    }

    public void stop() {
        stopRequested.set(true);
        if (workerThread != null) workerThread.interrupt();
    }

    private boolean forwardMessage(String fromChat, String toChat, int msgId) {
        // Manuelle JSON Konstruktion für HttpUtils (einfacher als Gson Dependency hier)
        // Hinweis: HttpUtils.post müsste implementiert sein oder wir nutzen Java standard URLConnection
        // Da HttpUtils.java im Framework nur GET hat, implementieren wir hier einen simplen POST.

        try {
            String url = String.format("http://localhost:8081/bot%s/forwardMessage", botToken);
            // Fallback auf public API wenn localhost nicht geht
            // url = String.format("https://api.telegram.org/bot%s/forwardMessage", botToken);

            java.net.URL obj = new java.net.URL(url);
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) obj.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            JsonObject json = new JsonObject();
            json.addProperty("chat_id", toChat);
            json.addProperty("from_chat_id", fromChat);
            json.addProperty("message_id", msgId);

            try (java.io.OutputStream os = con.getOutputStream()) {
                byte[] input = json.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            return con.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}