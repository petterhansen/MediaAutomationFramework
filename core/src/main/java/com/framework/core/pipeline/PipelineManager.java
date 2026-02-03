package com.framework.core.pipeline;

import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PipelineManager {
    private static final Logger logger = LoggerFactory.getLogger(PipelineManager.class);
    private final Kernel kernel;

    private final PriorityBlockingQueue<PipelineItem> dlQueue = new PriorityBlockingQueue<>();
    private final PriorityBlockingQueue<PipelineItem> procQueue = new PriorityBlockingQueue<>();
    private final BlockingQueue<PipelineItem> ulQueue = new LinkedBlockingQueue<>(); // Upload doesn't need priority

    private volatile PipelineItem currentDlItem;
    private volatile PipelineItem currentProcItem;
    private volatile PipelineItem currentUlItem;

    // Handlers (List for Chain of Responsibility)
    private final List<StageHandler<PipelineItem, File>> downloadHandlers = new CopyOnWriteArrayList<>();
    private final List<StageHandler<PipelineItem, List<File>>> processingHandlers = new CopyOnWriteArrayList<>();
    private final List<StageHandler<PipelineItem, Void>> uploadHandlers = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean surgeonMode = false;

    // Pipeline hooks for plugin extensibility
    private final List<PipelineHook> hooks = new CopyOnWriteArrayList<>();

    public PipelineManager(Kernel kernel) {
        this.kernel = kernel;
    }

    public void registerDownloadHandler(StageHandler<PipelineItem, File> handler) {
        downloadHandlers.add(0, handler); // Add to front (Newer = Higher Priority)
    }

    public void registerProcessingHandler(StageHandler<PipelineItem, List<File>> handler) {
        processingHandlers.add(0, handler);
    }

    public void registerUploadHandler(StageHandler<PipelineItem, Void> handler) {
        uploadHandlers.add(0, handler);
    }

    // Deprecated setters for backward compatibility, now just register
    public void setDownloadHandler(StageHandler<PipelineItem, File> handler) {
        registerDownloadHandler(handler);
    }

    public void setProcessingHandler(StageHandler<PipelineItem, List<File>> handler) {
        registerProcessingHandler(handler);
    }

    public void setUploadHandler(StageHandler<PipelineItem, Void> handler) {
        registerUploadHandler(handler);
    }

    private <I, O> StageHandler<I, O> getHandlerFor(List<StageHandler<I, O>> handlers, I item) {
        for (StageHandler<I, O> handler : handlers) {
            if (handler.supports(item)) {
                return handler;
            }
        }
        return null;
    }

    public void submit(PipelineItem item) {
        String creator = (String) item.getMetadata().get("creator");
        String checkId = (String) item.getMetadata().get("raw_id");
        if (checkId == null)
            checkId = item.getOriginalName();

        if (creator != null && kernel.getHistoryManager().isProcessed(creator, checkId)) {
            logger.debug("History Skip (Pipeline): {}", checkId);
            return;
        }

        dlQueue.put(item);
        logger.debug("Submitted to pipeline: {}", item.getOriginalName());
    }

    public void start() {
        if (running.getAndSet(true))
            return;
        new Thread(this::downloadLoop, "Pipe-DL").start();
        new Thread(this::processingLoop, "Pipe-Proc").start();
        new Thread(this::uploadLoop, "Pipe-UL").start();
    }

    public void stop() {
        running.set(false);
    }

    public void clear() {
        dlQueue.clear();
        procQueue.clear();
        ulQueue.clear();
    }

    public void setSurgeonMode(boolean active) {
        this.surgeonMode = active;
    }

    public boolean isSurgeonMode() {
        return surgeonMode;
    }

    public PipelineItem getCurrentDlItem() {
        return currentDlItem;
    }

    public PipelineItem getCurrentProcItem() {
        return currentProcItem;
    }

    public PipelineItem getCurrentUlItem() {
        return currentUlItem;
    }

    // Snapshot methods for monitoring
    public List<PipelineItem> getDlQueueSnapshot() {
        return new ArrayList<>(dlQueue);
    }

    public List<PipelineItem> getProcQueueSnapshot() {
        return new ArrayList<>(procQueue);
    }

    public List<PipelineItem> getUlQueueSnapshot() {
        return new ArrayList<>(ulQueue);
    }

    // Hook management
    public void registerHook(PipelineHook hook) {
        hooks.add(hook);
        logger.info("Pipeline hook registered: {}", hook.getClass().getSimpleName());
    }

    public void removeHook(PipelineHook hook) {
        hooks.remove(hook);
        logger.info("Pipeline hook removed: {}", hook.getClass().getSimpleName());
    }

    // --- LOOPS ---

    private void downloadLoop() {
        while (running.get()) {
            if (surgeonMode) {
                sleepBriefly();
                continue;
            }
            try {
                PipelineItem item = dlQueue.poll(1, TimeUnit.SECONDS);
                if (item == null)
                    continue;
                currentDlItem = item;
                try {
                    hooks.forEach(h -> h.beforeDownload(item));

                    StageHandler<PipelineItem, File> handler = getHandlerFor(downloadHandlers, item);
                    if (handler != null) {
                        File result = handler.process(item);
                        if (result != null && result.exists()) {
                            item.setDownloadedFile(result);

                            // Track download in database
                            String creator = (String) item.getMetadata().get("creator");
                            String source = (String) item.getMetadata().get("source");
                            kernel.getDatabaseService().markDownloaded(item.getSourceUrl(), creator, source);

                            hooks.forEach(h -> h.afterDownload(item, result));

                            procQueue.put(item);
                            logger.info("✅ Downloaded: {}", item.getSourceUrl());
                        } else {
                            throw new RuntimeException("Download returned null or non-existent file");
                        }
                    } else {
                        logger.warn("⚠️ No download handler found for: {}", item.getSourceUrl());
                    }
                } catch (Exception e) {
                    logger.error("❌ Download failed: {}", item.getSourceUrl(), e);
                    item.setLastError(e);
                    hooks.forEach(h -> h.onError(item, e, "download"));

                    if (item.shouldRetry()) {
                        long delay = item.getNextRetryDelay();
                        logger.warn("⏱️ Retrying in {}ms (attempt {}/{})",
                                delay, item.getRetryCount() + 1, item.getMaxRetries());

                        item.incrementRetry();
                        Thread.sleep(delay);
                        dlQueue.put(item);
                    } else {
                        logger.error("❌ Max retries exceeded for: {}", item.getSourceUrl());
                        kernel.getDatabaseService().markFailed(item.getSourceUrl(),
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    }
                } finally {
                    currentDlItem = null;
                }
            } catch (Exception e) {
                logger.error("DL Error", e);
            }
        }
    }

    private void processingLoop() {
        while (running.get()) {
            if (surgeonMode) {
                sleepBriefly();
                continue;
            }
            try {
                PipelineItem item = procQueue.poll(1, TimeUnit.SECONDS);
                if (item == null)
                    continue;
                currentProcItem = item;
                try {
                    hooks.forEach(h -> h.beforeProcessing(item));

                    StageHandler<PipelineItem, List<File>> handler = getHandlerFor(processingHandlers, item);
                    if (handler != null) {
                        List<File> result = handler.process(item);
                        if (result != null && !result.isEmpty()) {
                            item.setProcessedFiles(result);

                            // Track processing in database with file metadata
                            trackProcessedMetadata(item, result);

                            hooks.forEach(h -> h.afterProcessing(item, result));

                            ulQueue.put(item);
                            logger.info("✅ Processed: {}", item.getSourceUrl());
                        } else {
                            throw new RuntimeException("Processing returned null or empty list");
                        }
                    } else {
                        // Fallback: No processing needed/supported, pass through
                        // logger.debug("No processing handler, passing through.");
                        // item.setProcessedFiles(Collections.singletonList(item.getDownloadedFile()));
                        // ulQueue.put(item);
                        // WARN: Strict mode for now
                        logger.warn("⚠️ No processing handler found for: {}", item.getSourceUrl());
                    }
                } catch (Exception e) {
                    logger.error("❌ Processing failed: {}", item.getSourceUrl(), e);
                    item.setLastError(e);
                    hooks.forEach(h -> h.onError(item, e, "processing"));

                    if (item.shouldRetry()) {
                        long delay = item.getNextRetryDelay();
                        logger.warn("⏱️ Retrying processing in {}ms (attempt {}/{})",
                                delay, item.getRetryCount() + 1, item.getMaxRetries());

                        item.incrementRetry();
                        Thread.sleep(delay);
                        procQueue.put(item);
                    } else {
                        logger.error("❌ Max retries exceeded for processing: {}", item.getSourceUrl());
                        kernel.getDatabaseService().markFailed(item.getSourceUrl(),
                                "Processing: "
                                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                } finally {
                    currentProcItem = null;
                }
            } catch (Exception e) {
                logger.error("Proc Error", e);
            }
        }
    }

    // INTELLIGENTER UPLOAD LOOP (Batching)
    private void uploadLoop() {
        while (running.get()) {
            if (surgeonMode) {
                sleepBriefly();
                continue;
            }
            try {
                PipelineItem first = ulQueue.poll(1, TimeUnit.SECONDS);
                if (first == null)
                    continue;

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
                    targetItem = new PipelineItem(first.getSourceUrl(), "MediaGroup-" + batch.size(),
                            first.getParentTask());

                    // FIX: Kompilierfehler behoben (putAll statt setMetadata)
                    targetItem.getMetadata().putAll(first.getMetadata());

                    List<File> allFiles = new ArrayList<>();
                    for (PipelineItem p : batch) {
                        if (p.getProcessedFiles() != null)
                            allFiles.addAll(p.getProcessedFiles());
                    }
                    targetItem.setProcessedFiles(allFiles);
                    logger.info("📦 Batching: {} items merged into MediaGroup.", batch.size());
                }

                currentUlItem = targetItem;
                try {
                    hooks.forEach(h -> h.beforeUpload(targetItem));

                    StageHandler<PipelineItem, Void> handler = getHandlerFor(uploadHandlers, targetItem);
                    if (handler != null) {
                        handler.process(targetItem);

                        // Track uploads in database
                        for (PipelineItem p : batch) {
                            kernel.getDatabaseService().markUploaded(p.getSourceUrl());
                            if (p.getParentTask() != null)
                                p.getParentTask().incrementProcessed();
                        }

                        hooks.forEach(h -> h.afterUpload(targetItem));
                        logger.info("✅ Uploaded: {} items", batch.size());
                    } else {
                        logger.warn("⚠️ No upload handler found for batch.");
                    }
                } catch (Exception e) {
                    logger.error("❌ Upload failed for batch", e);
                    hooks.forEach(h -> h.onError(first, e, "upload"));

                    // For upload, retry the entire batch
                    boolean shouldRetryBatch = first.shouldRetry();

                    if (shouldRetryBatch) {
                        long delay = first.getNextRetryDelay();
                        logger.warn("⏱️ Retrying upload in {}ms (attempt {}/{})",
                                delay, first.getRetryCount() + 1, first.getMaxRetries());

                        // Increment retry for all items in batch
                        for (PipelineItem p : batch) {
                            p.setLastError(e);
                            p.incrementRetry();
                        }

                        Thread.sleep(delay);

                        // Re-queue all items
                        for (PipelineItem p : batch) {
                            ulQueue.put(p);
                        }
                    } else {
                        logger.error("❌ Max retries exceeded for upload batch");
                        for (PipelineItem p : batch) {
                            kernel.getDatabaseService().markFailed(p.getSourceUrl(),
                                    "Upload: "
                                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                        }
                    }
                } finally {
                    currentUlItem = null;
                }

            } catch (Exception e) {
                logger.error("UL Error", e);
            }
        }
    }

    /**
     * Track processed file metadata in database
     */
    private void trackProcessedMetadata(PipelineItem item, List<File> processedFiles) {
        if (processedFiles == null || processedFiles.isEmpty())
            return;

        File primaryFile = processedFiles.get(0);
        long fileSize = primaryFile.length();
        String fileName = primaryFile.getName();

        // Extract dimensions/duration if available in metadata
        int width = (Integer) item.getMetadata().getOrDefault("width", 0);
        int height = (Integer) item.getMetadata().getOrDefault("height", 0);
        double duration = ((Number) item.getMetadata().getOrDefault("duration", 0.0)).doubleValue();

        kernel.getDatabaseService().markProcessed(
                item.getSourceUrl(),
                fileName,
                fileSize,
                width,
                height,
                duration);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
        }
    }
}