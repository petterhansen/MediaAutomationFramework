package internal;

import com.framework.api.CommandHandler;
import com.framework.common.util.HttpUtils;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
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

    private final Map<String, CommandHandler> commandRegistry = new ConcurrentHashMap<>();

    // Scheduler für Auto-Delete Tasks
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Nutze den lokalen Server Port
    private static final String API_BASE = "http://localhost:8081/bot%s/";

    public TelegramListenerService(Kernel kernel, String botToken) {
        this.kernel = kernel;
        this.botToken = botToken;
        this.setName("TelegramListener");
    }

    public void setWizard(TelegramWizard wizard) {
        this.wizard = wizard;
    }

    public void registerCommand(String cmd, CommandHandler handler) {
        commandRegistry.put(cmd.toLowerCase(), handler);
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
                try { Thread.sleep(5000); } catch (Exception ex) {}
            }
        }
        scheduler.shutdown();
    }

    private void processUpdate(JsonObject update) {
        long updateId = update.get("update_id").getAsLong();
        lastUpdateId = updateId;

        // 1. Nachrichten (Befehle) verarbeiten
        if (update.has("message")) {
            JsonObject msg = update.getAsJsonObject("message");
            if (msg.has("text")) {
                String text = msg.get("text").getAsString();
                long chatId = msg.get("chat").getAsJsonObject().get("id").getAsLong();
                long messageId = msg.get("message_id").getAsLong();

                if (kernel.getUserManager() != null) {
                    kernel.getUserManager().trackUser(msg.get("from").getAsJsonObject());
                }

                if (wizard != null && wizard.isActive(chatId)) {
                    wizard.handleInput(chatId, text);
                    // Wizard Inputs auch löschen? Optional:
                    deleteMessage(chatId, messageId);
                    return;
                }

                handleCommand(text, chatId);

                // SOFORT NACH BEFEHLSVERARBEITUNG: User-Nachricht löschen
                deleteMessage(chatId, messageId);
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
        long messageId = msg.get("message_id").getAsLong();

        if ("CLOSE".equals(data)) {
            // Nachricht sofort löschen
            deleteMessage(chatId, messageId);
            // Callback beantworten (Ladekreis entfernen)
            answerCallbackQuery(id, "Geschlossen");
        }
    }

    private void handleCommand(String text, long chatId) {
        String[] parts = text.split(" ");
        String cmd = parts[0].toLowerCase();

        if (commandRegistry.containsKey(cmd)) {
            String[] args = Arrays.copyOfRange(parts, 1, parts.length);
            commandRegistry.get(cmd).handle(chatId, cmd, args);
            return;
        }

        if (cmd.equals("/dl")) {
            if (wizard != null && parts.length == 1) {
                wizard.startDlWizard(chatId);
                return;
            }
            if (parts.length > 2) {
                try {
                    int amount = Integer.parseInt(parts[1]);
                    String query = parts[2];
                    QueueTask task = new QueueTask("SEARCH_BATCH");
                    task.addParameter("query", query);
                    task.addParameter("amount", amount);
                    task.addParameter("source", "party");
                    task.addParameter("chatId", chatId);
                    kernel.getQueueManager().addTask(task);
                    sendText(chatId, "✅ Task gestartet: " + query);
                } catch (Exception e) {
                    sendText(chatId, "⚠️ Syntax: /dl [Anzahl] [Query]");
                }
            }
        } else if (cmd.equals("/status")) {
            sendText(chatId, "🟢 <b>System Online</b>\nQueue Size: " + kernel.getQueueManager().getQueueSize());
        }
    }

    /**
     * Sendet Text mit einem "Schließen"-Button und plant automatische Löschung.
     */
    public void sendText(long chatId, String text) {
        try {
            // JSON Body bauen für POST Request (mit Inline Keyboard)
            JsonObject json = new JsonObject();
            json.addProperty("chat_id", chatId);
            json.addProperty("text", text);
            json.addProperty("parse_mode", "HTML");

            // Keyboard hinzufügen
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

            // Message ID auslesen für Auto-Delete
            if (response != null) {
                JsonObject respRoot = JsonParser.parseString(response).getAsJsonObject();
                if (respRoot.get("ok").getAsBoolean()) {
                    long msgId = respRoot.getAsJsonObject("result").get("message_id").getAsLong();

                    // Auto-Delete nach 60 Sekunden planen
                    scheduler.schedule(() -> deleteMessage(chatId, msgId), 60, TimeUnit.SECONDS);
                }
            }

        } catch (Exception e) {
            logger.error("Failed to send text to {}", chatId, e);
        }
    }

    public void deleteMessage(long chatId, long messageId) {
        try {
            // Löschen via POST
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