package com.plugins.coremedia;

import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.queue.QueueTask;
import com.framework.core.queue.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LocalTaskExecutor implements TaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(LocalTaskExecutor.class);
    private final Kernel kernel;

    public LocalTaskExecutor(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void execute(QueueTask task) {
        String folderName = task.getString("folder");
        int limit = task.getInt("amount", -1);
        String initiatorChatId = task.getString("initiatorChatId");
        Integer initiatorThreadId = task.getParameter("initiatorThreadId") != null
                ? Integer.parseInt(task.getString("initiatorThreadId"))
                : null;

        File cacheDir = new File("media_cache");
        File target = new File(cacheDir, folderName);

        if (!target.exists()) {
            reportError(initiatorChatId, initiatorThreadId, "❌ Item not found: " + folderName);
            task.setStatus(QueueTask.Status.FAILED);
            return;
        }

        task.setStatus(QueueTask.Status.RUNNING);

        List<File> validFiles = new ArrayList<>();
        if (target.isFile()) {
            validFiles.add(target);
        } else {
            File[] files = target.listFiles();
            if (files == null || files.length == 0) {
                reportError(initiatorChatId, initiatorThreadId, "⚠️ Folder is empty: " + folderName);
                task.setStatus(QueueTask.Status.DONE);
                return;
            }
            // Filter valid files
            for (File f : files) {
                if (f.isFile() && !f.getName().startsWith(".")) {
                    validFiles.add(f);
                }
            }
            Collections.sort(validFiles);
        }

        int count = 0;
        int total = validFiles.size();
        if (limit > 0)
            total = Math.min(total, limit);

        task.setTotalItems(total);

        for (File file : validFiles) {
            if (limit > 0 && count >= limit)
                break;

            try {
                // Create minimal PipelineItem for local file
                PipelineItem item = new PipelineItem(
                        "local://" + folderName + "/" + file.getName(),
                        file.getName(),
                        task);

                // Set metadata manually
                item.getMetadata().put("creator", folderName); // Treat folder as creator
                item.getMetadata().put("source", "local");
                item.getMetadata().put("is_local", true);

                // Directly set downloaded file so it skips download stage
                item.setDownloadedFile(file);

                // Inject DIRECTLY into processing queue (skipping download handlers)
                // Note: We need a way to put into procQueue.
                // PipelineManager doesn't expose procQueue directly but we can submit() and
                // hope it flows?
                // Submitting usually goes to DL queue.
                // WORKAROUND: We will simulate a "Mock Download Handler" or just modify
                // pipeline to support pre-downloaded.
                // BETTER: Just submit normally, but register a "LocalDownloadHandler" that just
                // checks existence.
                // Actually, let's look at PipelineManager.submit().

                // PLAN B: Since we can't inject into ProcQueue easily without changing core,
                // we will use submit() and ensure our generic DL handler (CoreMediaPlugin)
                // handles "local://" schema
                // OR creates a specific LocalDownloadHandler.

                kernel.getPipelineManager().submit(item);

                count++;
                Thread.sleep(100); // Slight delay to prevent flooding
            } catch (Exception e) {
                logger.error("Failed to submit local file: " + file.getName(), e);
            }
        }

        task.setStatus(QueueTask.Status.DONE);
        reportInfo(initiatorChatId, initiatorThreadId,
                "✅ <b>Local Batch Processed:</b> " + count + " items from " + folderName);
    }

    private void reportError(String chatId, Integer threadId, String msg) {
        if (chatId != null) {
            try {
                long cid = Long.parseLong(chatId);
                kernel.sendMessage(cid, threadId, msg);
            } catch (Exception e) {
            }
        } else {
            logger.error(msg);
        }
    }

    private void reportInfo(String chatId, Integer threadId, String msg) {
        if (chatId != null) {
            try {
                long cid = Long.parseLong(chatId);
                kernel.sendMessage(cid, threadId, msg);
            } catch (Exception e) {
            }
        } else {
            logger.info(msg);
        }
    }
}
