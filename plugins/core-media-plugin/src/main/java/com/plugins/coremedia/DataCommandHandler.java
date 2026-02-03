package com.plugins.coremedia;

import com.framework.api.CommandHandler;
import com.framework.core.Kernel;
import com.framework.services.database.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class DataCommandHandler implements CommandHandler {
    private static final Logger logger = LoggerFactory.getLogger(DataCommandHandler.class);
    private final Kernel kernel;

    public DataCommandHandler(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void handle(long chatId, Integer threadId, String command, String[] args) {
        try {
            switch (command.toLowerCase()) {
                case "/history":
                    handleHistory(chatId, threadId, args);
                    break;
                case "/creators":
                case "/list":
                    handleCreators(chatId, threadId);
                    break;
            }
        } catch (Exception e) {
            logger.error("Data Command Error", e);
            kernel.sendMessage(chatId, threadId, "❌ <b>Error:</b> " + e.getMessage());
        }
    }

    private void handleHistory(long chatId, Integer threadId, String[] args) {
        if (args.length < 2) {
            kernel.sendMessage(chatId, threadId, "⚠️ Usage: <code>/history [list|clear] [creator]</code>");
            return;
        }

        String action = args[0].toLowerCase();
        // Args 1..N is the creator name (might contain spaces)
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            sb.append(args[i]).append(" ");
        }
        String creator = sb.toString().trim();

        if (action.equals("list")) {
            int count = kernel.getHistoryManager().getHistorySize(creator);
            List<String> last = kernel.getHistoryManager().getLastEntries(creator, 10);

            StringBuilder msg = new StringBuilder(
                    "📜 <b>History: " + escape(creator) + "</b> (" + count + " items)\n\n");
            if (last.isEmpty()) {
                msg.append("<i>No entries found.</i>");
            } else {
                for (String file : last) {
                    msg.append("• ").append(escape(file)).append("\n");
                }
                if (count > 10)
                    msg.append("\n<i>... and " + (count - 10) + " more.</i>");
            }
            kernel.sendMessage(chatId, threadId, msg.toString());

        } else if (action.equals("clear")) {
            // Delete history
            // TODO: Add confirmation logic? For now, direct execute as per user request
            // context
            boolean ok = kernel.getHistoryManager().deleteHistory(creator);
            if (ok) {
                kernel.sendMessage(chatId, threadId, "✅ History for <b>" + escape(creator) + "</b> deleted.");
            } else {
                kernel.sendMessage(chatId, threadId, "⚠️ Creator not found or empty.");
            }
        } else {
            kernel.sendMessage(chatId, threadId, "⚠️ Unknown action: " + action);
        }
    }

    private void handleCreators(long chatId, Integer threadId) {
        Map<String, DatabaseService.CreatorStatsDTO> stats = kernel.getDatabaseService().getCreatorStatistics();

        if (stats.isEmpty()) {
            kernel.sendMessage(chatId, threadId, "ℹ️ No Creators in the database.");
            return;
        }

        StringBuilder sb = new StringBuilder("📚 <b>All Creators (" + stats.size() + "):</b>\n\n");
        int count = 0;
        for (Map.Entry<String, DatabaseService.CreatorStatsDTO> entry : stats.entrySet()) {
            String name = entry.getKey(); // Already lowercase from query, but map key
            // Ideally capitalize or use original name if stored, but key is what we have
            // for now.
            // Actually, wait, the query returns lowercase key but if we want nice display
            // we might want original names.
            // But strict uniqueness is usually case-insensitive.

            DatabaseService.CreatorStatsDTO s = entry.getValue();

            String line = String.format("• <b>%s</b>: %d (🖼️%d 📹%d)\n",
                    escape(name), s.total, s.images, s.videos);

            if (sb.length() + line.length() > 3800) {
                sb.append("\n<i>... and " + (stats.size() - count) + " more.</i>");
                break;
            }
            sb.append(line);
            count++;
        }
        kernel.sendMessage(chatId, threadId, sb.toString());
    }

    private String escape(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
