package com.framework.core;

import com.framework.api.CommandHandler;
import com.framework.common.auth.AuthManager;
import com.framework.common.auth.UserManager;
import com.framework.core.config.ConfigManager;
import com.framework.core.pipeline.PipelineManager;
import com.framework.core.plugin.PluginLoader;
import com.framework.core.queue.QueueManager;
import com.framework.services.stats.HistoryManager;
import com.framework.services.stats.StatisticsManager;
import com.framework.web.DashboardServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class Kernel {
    private static final Logger logger = LoggerFactory.getLogger(Kernel.class);
    private static Kernel instance;

    // Infrastructure Managers
    private final ConfigManager configManager;
    private final QueueManager queueManager;
    private final PipelineManager pipelineManager;
    private final PluginLoader pluginLoader;
    private final HistoryManager historyManager;
    private final AuthManager authManager;
    private final StatisticsManager statisticsManager;
    private final UserManager userManager;
    private final DashboardServer dashboardServer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final File toolsDir;

    // Registry für Chat-Befehle (Plugins registrieren hier ihre Handler)
    private final Map<String, CommandHandler> commandRegistry = new ConcurrentHashMap<>();

    // Generic Message Sender (Damit Plugins Nachrichten verschicken können, ohne Telegram zu kennen)
    private BiConsumer<Long, String> messageSender;

    private Kernel() {
        this.toolsDir = new File("tools");
        if (!toolsDir.exists()) toolsDir.mkdirs();

        // 1. Core Services laden
        this.configManager = new ConfigManager(this);
        this.authManager = new AuthManager(this);
        this.statisticsManager = new StatisticsManager(this);
        this.historyManager = new HistoryManager(this);
        this.userManager = new UserManager(this);

        this.queueManager = new QueueManager(this);
        this.pipelineManager = new PipelineManager(this);

        this.pluginLoader = new PluginLoader(this);
        this.dashboardServer = new DashboardServer(this);
    }

    public static synchronized Kernel getInstance() {
        if (instance == null) instance = new Kernel();
        return instance;
    }

    public void start() {
        if (running.getAndSet(true)) return;
        logger.info("Kernel booting... ");

        // 2. Plugins laden
        // HIER passiert jetzt alles. Der Kernel macht selbst NICHTS mehr.
        // Die Plugins holen sich den PipelineManager und setzen die Handler.
        this.pluginLoader.loadPlugins();

        // 3. Server starten
        this.queueManager.start();
        this.pipelineManager.start();
        this.dashboardServer.start();

        logger.info("Kernel active. System is modular.");
    }

    // --- Plugin Communication API ---

    public void registerCommand(String cmd, CommandHandler handler) {
        commandRegistry.put(cmd.toLowerCase(), handler);
        logger.debug("Command registered: /{}", cmd);
    }

    public Map<String, CommandHandler> getCommandRegistry() {
        return commandRegistry;
    }

    public void registerMessageSender(BiConsumer<Long, String> sender) {
        this.messageSender = sender;
    }

    public void sendMessage(long chatId, String text) {
        if (messageSender != null) {
            messageSender.accept(chatId, text);
        } else {
            logger.warn("Kein MessageSender verfügbar (Telegram Plugin fehlt?). Nachricht an {}: {}", chatId, text);
        }
    }

    // --- Getter ---
    public File getToolsDir() { return toolsDir; }
    public ConfigManager getConfigManager() { return configManager; }
    public QueueManager getQueueManager() { return queueManager; }
    public PipelineManager getPipelineManager() { return pipelineManager; }
    public AuthManager getAuthManager() { return authManager; }
    public StatisticsManager getStatisticsManager() { return statisticsManager; }
    public UserManager getUserManager() { return userManager; }
    public HistoryManager getHistoryManager() { return historyManager; }
}