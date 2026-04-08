package com.plugins.party;

import com.framework.api.MediaPlugin;
import com.framework.api.DownloadSourceProvider;
import com.framework.common.DownloadRequest;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import com.plugins.party.internal.PartySource;

public class PartyPlugin implements MediaPlugin, DownloadSourceProvider {
    private Kernel kernel;

    @Override
    public String getName() {
        return "coomer";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.kernel = kernel;
        // Wir registrieren die Source beim QueueManager
        kernel.getQueueManager().registerExecutor("SEARCH_BATCH", new PartySource(kernel));

        // Register as a source provider
        kernel.getSourceDetectionService().registerProvider(this);

        // Also register 'kemono' as a secondary provider
        kernel.getSourceDetectionService().registerProvider(new DownloadSourceProvider() {
            @Override
            public String getName() { return "kemono"; }
            @Override
            public boolean canHandle(String query) { return query != null && query.contains("kemono.party"); }
            @Override
            public QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
                return PartyPlugin.this.createTask(req, chatId, threadId);
            }
        });

        // Register Commands
        kernel.registerCommand("/cm", (id, tid, c, a) -> handleSearchShortcut(id, tid, "coomer", a));
        kernel.registerCommand("/km", (id, tid, c, a) -> handleSearchShortcut(id, tid, "kemono", a));
    }

    private void handleSearchShortcut(long id, Integer threadId, String source, String[] args) {
        String query = String.join(" ", args).trim();
        addTask(query, source, id, threadId, "SEARCH_BATCH");
        send(id, threadId, "🔍 Search gestartet: " + source + " -> " + (query.isEmpty() ? "Popular" : query));
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
        return query != null && query.contains("coomer.party");
    }

    @Override
    public QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
        QueueTask task = new QueueTask("SEARCH_BATCH");
        
        task.addParameter("query", req.query() == null || req.query().isEmpty() ? "all" : req.query());
        task.addParameter("source", req.source() != null ? req.source() : "coomer");
        int amount = req.amount() > 0 ? req.amount() : 1;
        task.addParameter("amount", amount);

        if (req.filetype() != null) {
            task.addParameter("filetype", req.filetype());
        }
        if (req.service() != null) {
            task.addParameter("service", req.service());
        }

        if (chatId != 0) {
            task.addParameter("initiatorChatId", String.valueOf(chatId));
            if (threadId != null) {
                task.addParameter("initiatorThreadId", String.valueOf(threadId));
            }
        }

        return task;
    }
}