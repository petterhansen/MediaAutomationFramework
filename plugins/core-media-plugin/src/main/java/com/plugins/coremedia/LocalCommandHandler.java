package com.plugins.coremedia;

import com.framework.api.CommandHandler;
import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class LocalCommandHandler implements CommandHandler {
    private static final Logger logger = LoggerFactory.getLogger(LocalCommandHandler.class);
    private final Kernel kernel;

    public LocalCommandHandler(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void handle(long chatId, Integer threadId, String command, String[] args) {
        try {
            if (args.length < 1) {
                kernel.sendMessage(chatId, threadId,
                        "⚠️ Usage: <code>" + command + " [FolderName] [Amount optional]</code>");
                return;
            }

            String folderName = args[0];
            int amount = -1;
            String customCaption = null;

            // Parse remaining arguments
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("#")) {
                    customCaption = arg;
                } else {
                    try {
                        amount = Integer.parseInt(arg);
                    } catch (NumberFormatException e) {
                        // Not a number, likely part of complex folder naming if split by space
                    }
                }
            }

            File cacheDir = new File("media_cache");
            File target = new File(cacheDir, folderName);

            if (target.exists()) {
                QueueTask task = new QueueTask("LOCAL_TASK");
                if (target.isDirectory()) {
                    task.addParameter("folder", folderName);
                } else {
                    task.addParameter("file", folderName);
                }

                if (amount > 0)
                    task.addParameter("amount", amount);
                if (customCaption != null)
                    task.addParameter("caption", customCaption);
                task.addParameter("initiatorChatId", String.valueOf(chatId));
                if (threadId != null)
                    task.addParameter("initiatorThreadId", String.valueOf(threadId));

                kernel.getQueueManager().addTask(task);
                kernel.sendMessage(chatId, threadId, "📂 <b>Local Task started:</b> " + folderName);
            } else {
                kernel.sendMessage(chatId, threadId,
                        "❌ Item not found in media_cache: <code>" + folderName + "</code>");
            }

        } catch (Exception e) {
            logger.error("Local Command Error", e);
            kernel.sendMessage(chatId, threadId, "❌ <b>Error:</b> " + e.getMessage());
        }
    }
}
