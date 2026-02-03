package com.framework.core.pipeline;

import com.framework.core.queue.QueueTask;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class PipelineItem implements Comparable<PipelineItem> {

    /**
     * Priority levels for pipeline items
     */
    public enum Priority {
        LOW(1), NORMAL(5), HIGH(10);

        public final int value;

        Priority(int value) {
            this.value = value;
        }
    }

    private final String sourceUrl;
    private final String originalName;
    private final QueueTask parentTask;

    // Status-Felder
    private File downloadedFile;
    private List<File> processedFiles;

    // Retry mechanism fields
    private int retryCount = 0;
    private int maxRetries = 3;
    private long firstAttemptTime = System.currentTimeMillis();
    private Exception lastError = null;

    // Priority field
    private Priority priority = Priority.NORMAL;

    // Progress fields
    private double progressPercent = 0.0;
    private long speed = 0;
    private long eta = -1;

    private final Map<String, Object> metadata = new HashMap<>();

    public PipelineItem(String sourceUrl, String originalName, QueueTask parentTask) {
        this.sourceUrl = sourceUrl;
        this.originalName = originalName;
        this.parentTask = parentTask;
    }

    // Alias für Dashboard
    public String getName() {
        return originalName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getOriginalName() {
        return originalName;
    }

    public QueueTask getParentTask() {
        return parentTask;
    }

    public void setDownloadedFile(File f) {
        this.downloadedFile = f;
    }

    public File getDownloadedFile() {
        return downloadedFile;
    }

    public void setProcessedFiles(List<File> f) {
        this.processedFiles = f;
    }

    public List<File> getProcessedFiles() {
        return processedFiles;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    // Retry mechanism methods
    public boolean shouldRetry() {
        return retryCount < maxRetries;
    }

    public void incrementRetry() {
        retryCount++;
    }

    public long getNextRetryDelay() {
        // Exponential backoff: 1s, 2s, 4s, 8s...
        return 1000L * (long) Math.pow(2, retryCount);
    }

    public void setLastError(Exception e) {
        this.lastError = e;
    }

    public Exception getLastError() {
        return lastError;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getFirstAttemptTime() {
        return firstAttemptTime;
    }

    // Priority methods
    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public Priority getPriority() {
        return priority;
    }

    // Progress methods
    public double getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(double progressPercent) {
        this.progressPercent = progressPercent;
    }

    public long getSpeed() {
        return speed;
    }

    public void setSpeed(long speed) {
        this.speed = speed;
    }

    public long getEta() {
        return eta;
    }

    public void setEta(long eta) {
        this.eta = eta;
    }

    /**
     * Compare items by priority (higher priority first)
     */
    @Override
    public int compareTo(PipelineItem other) {
        // Higher priority value comes first
        return Integer.compare(other.priority.value, this.priority.value);
    }
}