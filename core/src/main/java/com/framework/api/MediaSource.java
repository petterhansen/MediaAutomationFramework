package com.framework.api;

import com.framework.core.queue.TaskExecutor;

/**
 * Markiert eine Implementierung als Medienquelle (z.B. Booru, Reddit, LocalCrawler).
 * Eine Source fungiert technisch als TaskExecutor, der Tasks abarbeitet und Items in die Pipeline legt.
 */
public interface MediaSource extends TaskExecutor {
    /**
     * Gibt den lesbaren Namen der Quelle zurück (z.B. "PartySource").
     */
    String getName();
}