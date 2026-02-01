package com.framework.core.pipeline;

import com.framework.core.queue.QueueTask;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class PipelineItem {
    private final String sourceUrl;
    private final String originalName;
    private final QueueTask parentTask;

    // Status-Felder
    private File downloadedFile;
    private List<File> processedFiles;

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

    public String getSourceUrl() { return sourceUrl; }
    public String getOriginalName() { return originalName; }
    public QueueTask getParentTask() { return parentTask; }

    public void setDownloadedFile(File f) { this.downloadedFile = f; }
    public File getDownloadedFile() { return downloadedFile; }

    public void setProcessedFiles(List<File> f) { this.processedFiles = f; }
    public List<File> getProcessedFiles() { return processedFiles; }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}