package com.framework.core;

import com.framework.api.CommandHandler;
import com.framework.common.auth.AuthManager;
import com.framework.common.auth.UserManager;
import com.framework.common.util.BlacklistManager;
import com.framework.core.config.ConfigManager;
import com.framework.core.pipeline.PipelineManager;
import com.framework.core.plugin.PluginLoader;
import com.framework.core.queue.QueueManager;
import com.framework.services.stats.ChatImporter;
import com.framework.services.stats.HistoryManager;
import com.framework.services.stats.StatisticsManager;
import com.framework.services.infrastructure.SystemStatsService;
import com.framework.services.database.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final SystemStatsService systemStatsService;
    private final ChatImporter chatImporter;
    private final BlacklistManager blacklistManager;
    private final DatabaseService databaseService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final File toolsDir;

    // --- SERVICE REGISTRY (Der Ersatz für harte Referenzen) ---
    // Hier legen Plugins ihre Instanzen ab (z.B. TranscoderService,
    // TelegramListener)
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    // Generic Message Sender
    private TriConsumer<Long, Integer, String> messageSender;

    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    private final Map<String, CommandHandler> commandRegistry = new ConcurrentHashMap<>();

    private Kernel() {
        this.toolsDir = new File("tools");
        if (!toolsDir.exists())
            toolsDir.mkdirs();

        // Initialize database service (lightweight H2 for Raspberry Pi)
        this.databaseService = new DatabaseService("tools/framework.db");

        this.configManager = new ConfigManager(this);
        this.authManager = new AuthManager(this);
        this.statisticsManager = new StatisticsManager(this);
        this.historyManager = new HistoryManager(this);
        this.userManager = new UserManager(this);
        this.blacklistManager = new BlacklistManager(this);
        this.systemStatsService = new SystemStatsService();
        this.chatImporter = new ChatImporter(this.statisticsManager);
        this.queueManager = new QueueManager(this);
        this.pipelineManager = new PipelineManager(this);
        this.pluginLoader = new PluginLoader(this);
    }

    public static synchronized Kernel getInstance() {
        if (instance == null)
            instance = new Kernel();
        return instance;
    }

    public void start() {
        if (running.getAndSet(true))
            return;
        logger.info("⚛️ Kernel booting...");

        this.systemStatsService.start();

        // Plugins laden (starten Dashboard, Sources, Telegram etc.)
        this.pluginLoader.loadPlugins();

        this.queueManager.start();
        this.pipelineManager.start();

        logger.info("✅ Kernel active.");
    }

    // --- SERVICE API ---

    public <T> void registerService(Class<T> clazz, T service) {
        services.put(clazz, service);
        logger.info("Service registered: {}", clazz.getSimpleName());
    }

    public <T> void unregisterService(Class<T> clazz) {
        services.remove(clazz);
        logger.info("Service DEREGISTERED: {}", clazz.getSimpleName());
    }

    public <T> T getService(Class<T> clazz) {
        return clazz.cast(services.get(clazz));
    }

    // --- Command & Message API ---

    public void registerCommand(String cmd, CommandHandler handler) {
        commandRegistry.put(cmd.toLowerCase(), handler);
    }

    public void unregisterCommand(String cmd) {
        commandRegistry.remove(cmd.toLowerCase());
        logger.info("Command DEREGISTERED: {}", cmd);
    }

    public Map<String, CommandHandler> getCommandRegistry() {
        return commandRegistry;
    }

    public void registerMessageSender(TriConsumer<Long, Integer, String> sender) {
        this.messageSender = sender;
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, null, text);
    }

    public void sendMessage(long chatId, Integer threadId, String text) {
        if (messageSender != null)
            messageSender.accept(chatId, threadId, text);
        else
            logger.warn("No MessageSender registered. Msg to {} (topic {}): {}", chatId, threadId, text);
    }

    // --- Getters ---
    public File getToolsDir() {
        return toolsDir;
    }

    public PluginLoader getPluginLoader() {
        return pluginLoader;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }

    public PipelineManager getPipelineManager() {
        return pipelineManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public StatisticsManager getStatisticsManager() {
        return statisticsManager;
    }

    public UserManager getUserManager() {
        return userManager;
    }

    public HistoryManager getHistoryManager() {
        return historyManager;
    }

    public SystemStatsService getSystemStatsService() {
        return systemStatsService;
    }

    public ChatImporter getChatImporter() {
        return chatImporter;
    }

    public BlacklistManager getBlacklistManager() {
        return blacklistManager;
    }

    public DatabaseService getDatabaseService() {
        return databaseService;
    }
}