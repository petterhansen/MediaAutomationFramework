import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.config.Configuration;
import com.plugins.telegram.internal.LocalBotServer;
import com.plugins.telegram.internal.TelegramListenerService;
import com.plugins.telegram.internal.TelegramSink;
import com.plugins.telegram.internal.TelegramWizard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelegramPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(TelegramPlugin.class);
    private LocalBotServer localServer;
    private TelegramListenerService listener;

    @Override
    public String getName() { return "TelegramIntegration"; }

    @Override
    public String getVersion() { return "2.1.0"; }

    @Override
    public void onEnable(Kernel kernel) {
        Configuration config = kernel.getConfigManager().getConfig();
        if (!config.telegramEnabled) return;

        // 1. Server starten (Teil des Plugins!)
        localServer = new LocalBotServer(kernel);
        localServer.start();

        // 2. Sink (Upload) registrieren
        TelegramSink sink = new TelegramSink(config.telegramToken, config.telegramAdminId, localServer.getApiUrl());
        kernel.getPipelineManager().setUploadHandler(sink);

        // 3. Listener starten
        listener = new TelegramListenerService(kernel, config.telegramToken);
        TelegramWizard wizard = new TelegramWizard(kernel, msg -> listener.sendText(msg.chatId(), msg.text()));
        listener.setWizard(wizard);

        // Listener registriert sich selbständig beim Start für Updates
        listener.start();

        // 4. Kommunikation zum Kernel herstellen
        // Damit CoreCommands antworten kann, ohne das Plugin zu kennen:
        kernel.registerMessageSender((chatId, text) -> listener.sendText(chatId, text));

        // Damit Befehle aus dem Kernel beim Listener landen:
        // Wir übergeben dem Listener die CommandRegistry des Kernels, damit er dort nachschauen kann
        listener.setCommandRegistryReference(kernel.getCommandRegistry());

        logger.info("✈️ Telegram Plugin online.");
    }

    @Override
    public void onDisable() {
        if (listener != null) listener.stopService();
        if (localServer != null) localServer.stop();
    }
}