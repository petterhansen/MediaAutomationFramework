package com.framework.core.pipeline;

import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PipelineManager {
    private static final Logger logger = LoggerFactory.getLogger(PipelineManager.class);
    private final Kernel kernel;

    private final BlockingQueue<PipelineItem> dlQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<PipelineItem> procQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<PipelineItem> ulQueue = new LinkedBlockingQueue<>();

    private volatile PipelineItem currentDlItem;
    private volatile PipelineItem currentProcItem;
    private volatile PipelineItem currentUlItem;

    private StageHandler<PipelineItem, File> downloadHandler;
    private StageHandler<PipelineItem, List<File>> processingHandler;
    private StageHandler<PipelineItem, Void> uploadHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean surgeonMode = false;

    public PipelineManager(Kernel kernel) {
        this.kernel = kernel;
    }

    public void setDownloadHandler(StageHandler<PipelineItem, File> handler) { this.downloadHandler = handler; }
    public void setProcessingHandler(StageHandler<PipelineItem, List<File>> handler) { this.processingHandler = handler; }
    public void setUploadHandler(StageHandler<PipelineItem, Void> handler) { this.uploadHandler = handler; }

    public void submit(PipelineItem item) {
        String creator = (String) item.getMetadata().get("creator");
        String checkId = (String) item.getMetadata().get("raw_id");
        if (checkId == null) checkId = item.getOriginalName();

        if (creator != null && kernel.getHistoryManager().isProcessed(creator, checkId)) {
            logger.debug("History Skip (Pipeline): {}", checkId);
            return;
        }

        try {
            dlQueue.put(item);
            logger.debug("Submitted to pipeline: {}", item.getOriginalName());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void start() {
        if (running.getAndSet(true)) return;
        new Thread(this::downloadLoop, "Pipe-DL").start();
        new Thread(this::processingLoop, "Pipe-Proc").start();
        new Thread(this::uploadLoop, "Pipe-UL").start();
    }

    public void stop() { running.set(false); }
    public void clear() { dlQueue.clear(); procQueue.clear(); ulQueue.clear(); }
    public void setSurgeonMode(boolean active) { this.surgeonMode = active; }
    public boolean isSurgeonMode() { return surgeonMode; }

    public PipelineItem getCurrentDlItem() { return currentDlItem; }
    public PipelineItem getCurrentProcItem() { return currentProcItem; }
    public PipelineItem getCurrentUlItem() { return currentUlItem; }

    // --- LOOPS ---

    private void downloadLoop() {
        while (running.get()) {
            if (surgeonMode) { sleepBriefly(); continue; }
            try {
                PipelineItem item = dlQueue.poll(1, TimeUnit.SECONDS);
                if (item == null) continue;
                currentDlItem = item;
                try {
                    if (downloadHandler != null) {
                        File result = downloadHandler.process(item);
                        if (result != null && result.exists()) {
                            item.setDownloadedFile(result);
                            markHistory(item); // Sofort sichern
                            procQueue.put(item);
                        } else {
                            logger.warn("Download failed: {}", item.getOriginalName());
                        }
                    }
                } finally { currentDlItem = null; }
            } catch (Exception e) { logger.error("DL Error", e); }
        }
    }

    private void processingLoop() {
        while (running.get()) {
            if (surgeonMode) { sleepBriefly(); continue; }
            try {
                PipelineItem item = procQueue.poll(1, TimeUnit.SECONDS);
                if (item == null) continue;
                currentProcItem = item;
                try {
                    if (processingHandler != null) {
                        List<File> result = processingHandler.process(item);
                        if (result != null && !result.isEmpty()) {
                            item.setProcessedFiles(result);
                            ulQueue.put(item);
                        }
                    }
                } finally { currentProcItem = null; }
            } catch (Exception e) { logger.error("Proc Error", e); }
        }
    }

    // INTELLIGENTER UPLOAD LOOP (Batching)
    private void uploadLoop() {
        while (running.get()) {
            if (surgeonMode) { sleepBriefly(); continue; }
            try {
                PipelineItem first = ulQueue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;

                // Batching starten
                List<PipelineItem> batch = new ArrayList<>();
                batch.add(first);
                long lastAddTime = System.currentTimeMillis();

                // Warte auf weitere Items (bis zu 10 oder Timeout)
                while (batch.size() < 10) {
                    PipelineItem next = ulQueue.peek();

                    if (next != null) {
                        batch.add(ulQueue.poll());
                        lastAddTime = System.currentTimeMillis();
                    } else {
                        // Queue leer: Prüfen ob in DL/Proc noch was arbeitet
                        boolean upstreamBusy = !procQueue.isEmpty() || currentProcItem != null ||
                                !dlQueue.isEmpty() || currentDlItem != null;

                        if (upstreamBusy) {
                            // Wenn Upstream aktiv, warten wir kurz (max 2s seit letztem Item)
                            if (System.currentTimeMillis() - lastAddTime < 2000) {
                                Thread.sleep(100);
                            } else {
                                break; // Timeout -> Senden was wir haben
                            }
                        } else {
                            break; // Nichts mehr in der Pipeline -> Senden
                        }
                    }
                }

                PipelineItem targetItem;
                if (batch.size() == 1) {
                    targetItem = batch.get(0);
                } else {
                    // Virtuelles Group-Item erstellen
                    targetItem = new PipelineItem(first.getSourceUrl(), "MediaGroup-" + batch.size(), first.getParentTask());

                    // FIX: Kompilierfehler behoben (putAll statt setMetadata)
                    targetItem.getMetadata().putAll(first.getMetadata());

                    List<File> allFiles = new ArrayList<>();
                    for (PipelineItem p : batch) {
                        if (p.getProcessedFiles() != null) allFiles.addAll(p.getProcessedFiles());
                    }
                    targetItem.setProcessedFiles(allFiles);
                    logger.info("📦 Batching: {} items merged into MediaGroup.", batch.size());
                }

                currentUlItem = targetItem;
                try {
                    if (uploadHandler != null) {
                        uploadHandler.process(targetItem);

                        // Stats inkrementieren
                        for (PipelineItem p : batch) {
                            if (p.getParentTask() != null) p.getParentTask().incrementProcessed();
                        }
                    }
                } finally { currentUlItem = null; }

            } catch (Exception e) { logger.error("UL Error", e); }
        }
    }

    private void markHistory(PipelineItem item) {
        String creator = (String) item.getMetadata().get("creator");
        String rawId = (String) item.getMetadata().get("raw_id");
        String saveId = (rawId != null) ? rawId : item.getOriginalName();
        if (creator != null) kernel.getHistoryManager().markAsProcessed(creator, saveId);
    }

    private void sleepBriefly() { try { Thread.sleep(1000); } catch (Exception e){} }
}