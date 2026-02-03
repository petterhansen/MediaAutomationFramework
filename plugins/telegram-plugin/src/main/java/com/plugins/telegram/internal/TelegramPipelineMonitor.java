package com.plugins.telegram.internal;

import com.framework.core.Kernel;
import com.framework.core.pipeline.PipelineItem;
import com.framework.core.pipeline.PipelineManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

public class TelegramPipelineMonitor {
    private static final Logger logger = LoggerFactory.getLogger(TelegramPipelineMonitor.class);
    private final Kernel kernel;
    private final TelegramListenerService listener;
    private final PipelineManager pipelineManager;

    private Timer updateTimer;
    private final AtomicLong currentMessageId = new AtomicLong(0);
    private long currentChatId = 0;
    private String lastContent = "";

    public TelegramPipelineMonitor(Kernel kernel, TelegramListenerService listener) {
        this.kernel = kernel;
        this.listener = listener;
        this.pipelineManager = kernel.getPipelineManager();
    }

    public void start(long chatId) {
        stop();
        this.currentChatId = chatId;

        // Send initial message
        long msgId = listener.sendTextAndGetId(chatId, "⏳ <b>Starting Dashboard...</b>");
        if (msgId != -1) {
            currentMessageId.set(msgId);
            startLoop();
        } else {
            logger.error("Could not start monitor, failed to send init message.");
        }
    }

    public void stop() {
        if (updateTimer != null) {
            updateTimer.cancel();
            updateTimer = null;
        }
        currentMessageId.set(0);
        lastContent = "";
    }

    private void startLoop() {
        updateTimer = new Timer();
        updateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                update();
            }
        }, 0, 3000);
    }

    private void update() {
        long msgId = currentMessageId.get();
        if (msgId == 0)
            return;

        try {
            StringBuilder sb = new StringBuilder("🎛 <b>LIVE PIPELINE STATUS</b>\n\n");

            // --- 1. DOWNLOAD ---
            PipelineItem dlItem = pipelineManager.getCurrentDlItem();
            sb.append("⬇️ <b>Download:</b> ");
            if (dlItem != null) {
                sb.append("<code>").append(escape(getCreator(dlItem))).append("</code>\n");

                int pct = (int) dlItem.getProgressPercent();
                long speed = dlItem.getSpeed();
                long eta = dlItem.getEta();

                sb.append("   └ ").append(progressBar(pct)).append(" ").append(pct).append("%");
                if (speed > 0)
                    sb.append(" | ").append(formatSpeed(speed));
                if (eta > 0)
                    sb.append(" | ETA: ").append(formatSeconds(eta));
                sb.append("\n");
            } else {
                sb.append("<i>Empty</i>\n");
            }
            appendQueue(sb, pipelineManager.getDlQueueSnapshot());

            // --- 2. PROCESSING ---
            sb.append("\n⚙️ <b>Processing:</b> ");
            PipelineItem procItem = pipelineManager.getCurrentProcItem();

            if (procItem != null) {
                sb.append("<code>").append(escape(getCreator(procItem))).append("</code>\n");
                int pct = (int) procItem.getProgressPercent();
                if (pct > 0) {
                    sb.append("   └ ").append(progressBar(pct)).append(" ").append(pct).append("%\n");
                } else {
                    sb.append("   └ <i>Processing...</i>\n");
                }
            } else {
                sb.append("<i>Empty</i>\n");
            }
            appendQueue(sb, pipelineManager.getProcQueueSnapshot());

            // --- 3. UPLOAD ---
            sb.append("\n⬆️ <b>Upload:</b> ");
            PipelineItem ulItem = pipelineManager.getCurrentUlItem();

            if (ulItem != null) {
                sb.append("<code>").append(escape(getCreator(ulItem))).append("</code>\n");
            } else {
                sb.append("<i>Empty</i>\n");
            }
            appendQueue(sb, pipelineManager.getUlQueueSnapshot());

            // --- FOOTER ---
            sb.append("\n📚 <b>Main Queue:</b> ").append(kernel.getQueueManager().getQueueSize()).append(" Tasks");
            if (kernel.getQueueManager().isPaused())
                sb.append(" (⏸ PAUSED)");

            String newContent = sb.toString();
            if (!newContent.equals(lastContent)) {
                listener.editMessageText(currentChatId, msgId, newContent);
                lastContent = newContent;
            }

        } catch (Exception e) {
            logger.error("Monitor Update Error", e);
            stop();
        }
    }

    private void appendQueue(StringBuilder sb, List<PipelineItem> queue) {
        if (!queue.isEmpty()) {
            sb.append("   <i>Queue (").append(queue.size()).append("):</i> ");
            int limit = 2;
            for (int i = 0; i < Math.min(queue.size(), limit); i++) {
                sb.append(escape(getCreator(queue.get(i))));
                if (i < limit - 1)
                    sb.append(", ");
            }
            if (queue.size() > limit)
                sb.append(", ...");
            sb.append("\n");
        }
    }

    private String getCreator(PipelineItem item) {
        if (item.getMetadata().containsKey("creator")) {
            return (String) item.getMetadata().get("creator");
        }
        return item.getOriginalName();
    }

    private String escape(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String progressBar(int pct) {
        int bars = pct / 10;
        return "[" + "█".repeat(bars) + "░".repeat(10 - bars) + "]";
    }

    private String formatSeconds(long s) {
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024)
            return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024)
            return (bytesPerSec / 1024) + " KB/s";
        return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }
}
