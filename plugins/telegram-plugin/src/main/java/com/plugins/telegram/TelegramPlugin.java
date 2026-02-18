package com.plugins.telegram;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.config.Configuration;
import com.plugins.telegram.internal.LocalBotServer;
import com.plugins.telegram.internal.TelegramListenerService;
import com.plugins.telegram.internal.TelegramSink;
import com.plugins.telegram.internal.TelegramWizard;
import com.plugins.telegram.internal.TelegramPipelineMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelegramPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(TelegramPlugin.class);
    private LocalBotServer localServer;
    private TelegramListenerService listener;
    private TelegramPipelineMonitor monitor;

    @Override
    public String getName() {
        return "TelegramIntegration";
    }

    @Override
    public String getVersion() {
        return "2.1.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        Configuration config = kernel.getConfigManager().getConfig();
        if (!config.telegramEnabled)
            return;

        setupDefaults(kernel);

        // 1. Server starten (Teil des Plugins!)
        localServer = new LocalBotServer(kernel);
        localServer.start();

        // 2. Sink (Upload) registrieren
        TelegramSink sink = new TelegramSink(config.telegramToken, config.telegramAdminId, localServer.getApiUrl());
        kernel.getPipelineManager().setUploadHandler(sink);

        // WARTEN bis Server da ist (max 10s)
        logger.info("⏳ Warte auf Telegram Local Server...");
        for (int i = 0; i < 20; i++) {
            if (localServer.isApiReachable())
                break;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
        }

        if (!localServer.isApiReachable()) {
            logger.error("❌ Telegram Local Server nicht erreichbar! Plugin startet eventuell fehlerhaft.");
        } else {
            logger.info("✅ Telegram Local Server ist erreichbar.");
        }

        // 3. Listener starten
        listener = new TelegramListenerService(kernel, config.telegramToken, config.telegramAllowedChats);
        TelegramWizard wizard = new TelegramWizard(kernel,
                msg -> listener.sendText(msg.chatId(), msg.threadId(), msg.text()));
        listener.setWizard(wizard);

        // Listener registriert sich selbständig beim Start für Updates
        listener.start();

        // 4. Monitor starten (Dashboard)
        monitor = new TelegramPipelineMonitor(kernel, listener);
        listener.setMonitor(monitor);

        // 5. Kommunikation zum Kernel herstellen
        // Damit CoreCommands antworten kann, ohne das Plugin zu kennen:
        kernel.registerMessageSender((chatId, threadId, text) -> listener.sendText(chatId, threadId, text));

        // Damit Befehle aus dem Kernel beim Listener landen:
        // Wir übergeben dem Listener die CommandRegistry des Kernels, damit er dort
        // nachschauen kann
        listener.setCommandRegistryReference(kernel.getCommandRegistry());

        logger.info("✈️ Telegram Plugin online.");
    }

    @Override
    public void onDisable() {
        if (monitor != null)
            monitor.stop();
        if (listener != null)
            listener.stopService();
        if (localServer != null)
            localServer.stop();
    }

    private void setupDefaults(Kernel kernel) {
        Configuration config = kernel.getConfigManager().getConfig();
        boolean dirty = false;

        if (config.getPluginSetting(getName(), "apiId", "").isEmpty()) {
            config.setPluginSetting(getName(), "apiId", "");
            dirty = true;
        }
        if (config.getPluginSetting(getName(), "apiHash", "").isEmpty()) {
            config.setPluginSetting(getName(), "apiHash", "");
            dirty = true;
        }
        if (config.getPluginSetting(getName(), "deleteUserMessages", "").isEmpty()) {
            config.setPluginSetting(getName(), "deleteUserMessages", "false");
            dirty = true;
        }

        if (dirty) {
            kernel.getConfigManager().saveConfig();
        }
    }
}