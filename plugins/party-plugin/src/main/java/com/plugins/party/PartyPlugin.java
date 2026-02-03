package com.plugins.party;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
// PartySource liegt jetzt HIER im Package, nicht mehr im Framework
import com.plugins.party.internal.PartySource;

public class PartyPlugin implements MediaPlugin {
    private Kernel kernel;

    @Override
    public String getName() {
        return "PartySource";
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
    }
}