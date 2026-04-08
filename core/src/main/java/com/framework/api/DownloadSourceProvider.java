package com.framework.api;

import com.framework.common.DownloadRequest;
import com.framework.core.queue.QueueTask;

/**
 * Interface for plugins to provide custom source detection and task building logic.
 * Enables the central /dl command to dynamically handle new sources.
 */
public interface DownloadSourceProvider {
    
    /**
     * Unique name of the source (e.g., "youtube", "booru", "gallery-dl").
     * This matching name allows users to explicitly specify the source via `/dl X Y Z [source]`.
     */
    String getName();

    /**
     * Determines whether this provider can handle the given query automatically.
     * 
     * @param query The user's search query or URL.
     * @return true if this provider accepts the query.
     */
    boolean canHandle(String query);

    /**
     * Builds the specific QueueTask for this source.
     * 
     * @param req The parsed download request DTO.
     * @param chatId The chat ID of the initiator (0 if none).
     * @param threadId The thread ID of the initiator (can be null).
     * @return The configured QueueTask ready for the QueueManager.
     */
    QueueTask createTask(DownloadRequest req, long chatId, Integer threadId);
}
