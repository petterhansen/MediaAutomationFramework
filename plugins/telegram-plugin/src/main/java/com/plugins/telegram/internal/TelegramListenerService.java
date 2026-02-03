package com.plugins.telegram.internal;

import com.framework.api.CommandHandler;
import com.framework.common.util.HttpUtils;
import com.framework.core.Kernel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TelegramListenerService extends Thread {
    private static final Logger logger = LoggerFactory.getLogger(TelegramListenerService.class);
    private final Kernel kernel;
    private final String botToken;
    private boolean running = true;
    private long lastUpdateId = 0;
    private TelegramWizard wizard;
    private final java.util.Set<Long> allowedChatIds = new java.util.HashSet<>();

    // Lokale Registry (nur für Plugin-interne Befehle)
    private final Map<String, CommandHandler> localRegistry = new ConcurrentHashMap<>();

    // Referenz auf die Kernel-Registry (für Core-Befehle wie /help, /status)
    private Map<String, CommandHandler> kernelRegistryReference;

    // Scheduler für Auto-Delete Tasks
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Nutze den lokalen Server Port
    private static final String API_BASE = "http://localhost:8081/bot%s/";

    public TelegramListenerService(Kernel kernel, String botToken, String whitelist) {
        this.kernel = kernel;
        this.botToken = botToken;
        this.setName("TelegramListener");
        parseWhitelist(whitelist);
    }

    private void parseWhitelist(String whitelist) {
        if (whitelist == null || whitelist.trim().isEmpty())
            return;
        String[] parts = whitelist.split(",");
        for (String p : parts) {
            try {
                allowedChatIds.add(Long.parseLong(p.trim()));
            } catch (NumberFormatException e) {
                logger.warn("Invalid Chat ID in whitelist: {}", p);
            }
        }
    }

    private boolean isChatAllowed(long chatId) {
        // Wenn keine Whitelist definiert, ist alles erlaubt (Abwärtskompatibilität)
        // ODER der Admin ist immer erlaubt
        if (allowedChatIds.isEmpty())
            return true;

        String adminId = kernel.getConfigManager().getConfig().telegramAdminId;
        if (adminId != null && !adminId.isEmpty() && String.valueOf(chatId).equals(adminId)) {
            return true;
        }

        return allowedChatIds.contains(chatId);
    }

    public void setWizard(TelegramWizard wizard) {
        this.wizard = wizard;
    }

    /**
     * Diese Methode fehlte und verursachte den Fehler.
     * Sie ermöglicht dem Plugin, die globale Command-Registry des Kernels zu
     * übergeben.
     */
    public void setCommandRegistryReference(Map<String, CommandHandler> registry) {
        this.kernelRegistryReference = registry;
    }

    public void registerCommand(String cmd, CommandHandler handler) {
        localRegistry.put(cmd.toLowerCase(), handler);
    }

    @Override
    public void run() {
        logger.info("TelegramListener gestartet. Polling via Local Bot API...");
        while (running) {
            try {
                String url = String.format(API_BASE + "getUpdates?offset=%d&timeout=10", botToken, lastUpdateId + 1);
                String json = HttpUtils.get(url, null);

                if (json != null) {
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    if (root.get("ok").getAsBoolean()) {
                        JsonArray updates = root.getAsJsonArray("result");
                        for (JsonElement el : updates) {
                            processUpdate(el.getAsJsonObject());
                        }
                    }
                }
                Thread.sleep(500); // Etwas schnelleres Polling
            } catch (Exception e) {
                logger.error("Listener Error", e);
                try {
                    Thread.sleep(5000);
                } catch (Exception ex) {
                }
            }
        }
        scheduler.shutdown();
    }

    private void processUpdate(JsonObject update) {
        long updateId = update.get("update_id").getAsLong();
        lastUpdateId = updateId;

        // 1. Nachrichten (Befehle) verarbeiten
        if (update.has("message") || update.has("channel_post")) {
            boolean isChannel = update.has("channel_post");
            JsonObject msg = isChannel ? update.getAsJsonObject("channel_post") : update.getAsJsonObject("message");

            long chatId = msg.get("chat").getAsJsonObject().get("id").getAsLong();
            if (!isChatAllowed(chatId)) {
                return;
            }

            long messageId = msg.get("message_id").getAsLong();
            Integer threadId = msg.has("message_thread_id") ? msg.get("message_thread_id").getAsInt() : null;

            // User tracken
            if (msg.has("from") && kernel.getUserManager() != null) {
                kernel.getUserManager().trackUser(msg.get("from").getAsJsonObject());
            }

            String text = "";
            if (msg.has("text"))
                text = msg.get("text").getAsString();
            else if (msg.has("caption"))
                text = msg.get("caption").getAsString();

            if (!isChannel) {
                // In Gruppen/DMs: Wizard oder Command
                if (!text.isEmpty() && wizard != null && wizard.isActive(chatId)) {
                    wizard.handleInput(chatId, text);
                } else if (!text.isEmpty()) {
                    handleCommand(text, chatId, threadId);
                }

                // IMMER löschen (außer in Channels), um den Chat sauber zu halten
                deleteMessage(chatId, messageId);
            } else {
                // In Channels: Nur Commands (kein Wizard, kein Löschen von Posts)
                if (!text.isEmpty()) {
                    handleCommand(text, chatId, threadId);
                }
            }
        }

        // 2. Button Klicks (Callback Queries) verarbeiten
        if (update.has("callback_query")) {
            handleCallback(update.getAsJsonObject("callback_query"));
        }
    }

    private void handleCallback(JsonObject callback) {
        String id = callback.get("id").getAsString();
        String data = callback.has("data") ? callback.get("data").getAsString() : "";
        JsonObject msg = callback.getAsJsonObject("message");
        long chatId = msg.getAsJsonObject("chat").get("id").getAsLong();

        if (!isChatAllowed(chatId)) {
            logger.debug("Ignoring callback from unauthorized chat: {}", chatId);
            return;
        }

        long messageId = msg.get("message_id").getAsLong();

        if ("CLOSE".equals(data)) {
            deleteMessage(chatId, messageId);
            answerCallbackQuery(id, "Geschlossen");
        }
    }

    private void handleCommand(String text, long chatId, Integer threadId) {
        String[] parts = text.split(" ");
        String cmd = parts[0].toLowerCase();
        String[] args = (parts.length > 1) ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        // 1. Zuerst in der lokalen Plugin-Registry schauen
        if (localRegistry.containsKey(cmd)) {
            localRegistry.get(cmd).handle(chatId, threadId, cmd, args);
            return;
        }

        // 2. Dann in der globalen Kernel-Registry schauen (wenn vorhanden)
        if (kernelRegistryReference != null && kernelRegistryReference.containsKey(cmd)) {
            kernelRegistryReference.get(cmd).handle(chatId, threadId, cmd, args);
            return;
        }

        // 3. Fallback for other hardcoded commands
        if (cmd.equals("/status")) {
            // Falls /status nicht im Kernel registriert ist, hier ein Fallback
            sendText(chatId, threadId,
                    "🟢 <b>System Online</b>\nQueue Size: " + kernel.getQueueManager().getQueueSize());
        }
    }

    public void sendText(long chatId, String text) {
        sendText(chatId, null, text);
    }

    public long sendTextAndGetId(long chatId, String text) {
        return sendTextAndGetId(chatId, null, text);
    }

    public long sendTextAndGetId(long chatId, Integer threadId, String text) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("chat_id", chatId);
            if (threadId != null) {
                json.addProperty("message_thread_id", threadId);
            }
            json.addProperty("text", text);
            json.addProperty("parse_mode", "HTML");

            // No buttons for status messages usually, or add if needed
            // For now, simple text

            String url = String.format(API_BASE + "sendMessage", botToken);
            String response = HttpUtils.postJson(url, json.toString());

            if (response != null) {
                JsonObject respRoot = JsonParser.parseString(response).getAsJsonObject();
                if (respRoot.get("ok").getAsBoolean()) {
                    return respRoot.getAsJsonObject("result").get("message_id").getAsLong();
                }
            }
        } catch (Exception e) {
            logger.error("Failed to send text to {}", chatId, e);
        }
        return -1;
    }

    public void editMessageText(long chatId, long messageId, String text) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("chat_id", chatId);
            json.addProperty("message_id", messageId);
            json.addProperty("text", text);
            json.addProperty("parse_mode", "HTML");

            String url = String.format(API_BASE + "editMessageText", botToken);
            HttpUtils.postJson(url, json.toString());
        } catch (Exception e) {
            logger.error("Failed to edit message {} in {}", messageId, chatId, e);
        }
    }

    public void sendText(long chatId, Integer threadId, String text) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("chat_id", chatId);
            if (threadId != null) {
                json.addProperty("message_thread_id", threadId);
            }
            json.addProperty("text", text);
            json.addProperty("parse_mode", "HTML");

            JsonObject inlineKeyboard = new JsonObject();
            JsonArray rows = new JsonArray();
            JsonArray row = new JsonArray();
            JsonObject button = new JsonObject();
            button.addProperty("text", "❌ Schließen");
            button.addProperty("callback_data", "CLOSE");
            row.add(button);
            rows.add(row);
            inlineKeyboard.add("inline_keyboard", rows);

            json.add("reply_markup", inlineKeyboard);

            String url = String.format(API_BASE + "sendMessage", botToken);
            String response = HttpUtils.postJson(url, json.toString());

            if (response != null) {
                JsonObject respRoot = JsonParser.parseString(response).getAsJsonObject();
                if (respRoot.get("ok").getAsBoolean()) {
                    long msgId = respRoot.getAsJsonObject("result").get("message_id").getAsLong();
                    scheduler.schedule(() -> deleteMessage(chatId, msgId), 10, TimeUnit.SECONDS);
                }
            }

        } catch (Exception e) {
            logger.error("Failed to send text to {}", chatId, e);
        }
    }

    public void deleteMessage(long chatId, long messageId) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("chat_id", chatId);
            json.addProperty("message_id", messageId);

            String url = String.format(API_BASE + "deleteMessage", botToken);
            HttpUtils.postJson(url, json.toString());
        } catch (Exception e) {
            logger.error("Failed to delete message {} in {}", messageId, chatId, e);
        }
    }

    private void answerCallbackQuery(String callbackId, String text) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("callback_query_id", callbackId);
            json.addProperty("text", text);

            String url = String.format(API_BASE + "answerCallbackQuery", botToken);
            HttpUtils.postJson(url, json.toString());
        } catch (Exception e) {
            logger.error("Failed to answer callback", e);
        }
    }

    public void stopService() {
        running = false;
        scheduler.shutdownNow();
    }
}