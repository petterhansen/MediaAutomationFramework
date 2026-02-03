package com.plugins.safety.internal;

import com.plugins.telegram.internal.TelegramListenerService;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;

public class SafetyTelegramHandler {
    private static final Logger logger = LoggerFactory.getLogger(SafetyTelegramHandler.class);
    private final ContentScannerService scannerService;
    private final long adminChatId;
    private final long alertChatId;
    private final String botToken;

    public SafetyTelegramHandler(ContentScannerService scannerService, long adminChatId, long alertChatId,
            String botToken) {
        this.scannerService = scannerService;
        this.adminChatId = adminChatId;
        this.alertChatId = alertChatId;
        this.botToken = botToken;
    }

    public void handleUpdate(JsonObject update, JsonObject message) {
        // Logic to handle safety-specific commands or alerts
    }

    private void sendAlert(TelegramListenerService listener, String text) {
        if (listener != null) {
            listener.sendText(alertChatId, text);
        }
    }

    private void secureDelete(File file) {
        if (file.exists()) {
            file.delete();
        }
    }
}
