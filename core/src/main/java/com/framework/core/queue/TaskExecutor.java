package com.framework.core.queue;

/**
 * Contract für Komponenten, die QueueTasks verarbeiten können.
 */
public interface TaskExecutor {
    /**
     * Führt den Task aus.
     * @param task Der generische Task mit Payload.
     */
    void execute(QueueTask task);
}