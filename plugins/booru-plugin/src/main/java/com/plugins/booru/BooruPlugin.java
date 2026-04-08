package com.plugins.booru;

import com.framework.api.MediaPlugin;
import com.framework.api.DownloadSourceProvider;
import com.framework.common.DownloadRequest;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import com.plugins.booru.internal.BooruSource;

public class BooruPlugin implements MediaPlugin, DownloadSourceProvider {
    private Kernel kernel;

    @Override
    public String getName() {
        return "booru";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;
        kernel.getQueueManager().registerExecutor("BOORU_BATCH", new BooruSource(kernel));

        // Register as a source provider
        kernel.getSourceDetectionService().registerProvider(this);

        // Register Commands
        kernel.registerCommand("/r34", (id, tid, c, a) -> handleBooruShortcut(id, tid, "r34", a));
        kernel.registerCommand("/xb", (id, tid, c, a) -> handleBooruShortcut(id, tid, "xb", a));
        kernel.registerCommand("/sb", (id, tid, c, a) -> handleBooruShortcut(id, tid, "sb", a));
    }

    private void handleBooruShortcut(long id, Integer threadId, String source, String[] args) {
        String query = String.join(" ", args).trim();
        addTask(query, source, id, threadId, "BOORU_BATCH");
        send(id, threadId, "🎨 Booru gestartet: " + source + " -> " + (query.isEmpty() ? "Random" : query));
    }

    private void addTask(String query, String source, long chatId, Integer threadId, String type) {
        QueueTask task = new QueueTask(type);
        task.addParameter("query", query.isEmpty() ? "all" : query);
        task.addParameter("source", source);
        task.addParameter("initiatorChatId", String.valueOf(chatId));
        if (threadId != null) {
            task.addParameter("initiatorThreadId", String.valueOf(threadId));
        }
        task.addParameter("amount", 1);
        kernel.getQueueManager().addTask(task);
    }

    private void send(long chatId, Integer threadId, String text) {
        kernel.sendMessage(chatId, threadId, text);
    }

    @Override
    public void onDisable() {
        if (kernel != null) {
            kernel.getSourceDetectionService().unregisterProvider(this);
        }
    }

    // --- DownloadSourceProvider Implementation ---

    @Override
    public boolean canHandle(String query) {
        // Booru tags typically have spaces or underscores and are not URLs.
        if (query == null || query.trim().isEmpty() || query.startsWith("http")) return false;
        return query.contains(" ") || query.contains("_");
    }

    @Override
    public QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
        QueueTask task = new QueueTask("BOORU_BATCH");

        if (chatId != 0) {
            task.addParameter("initiatorChatId", String.valueOf(chatId));
            if (threadId != null) {
                task.addParameter("initiatorThreadId", String.valueOf(threadId));
            }
        }

        task.addParameter("tags", req.query());
        int amount = req.amount() > 0 ? req.amount() : 1;
        task.addParameter("amount", amount);
        
        if (req.filetype() != null) {
            task.addParameter("filetype", req.filetype());
        }

        return task;
    }
}