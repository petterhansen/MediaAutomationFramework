package com.framework.core.pipeline;

import java.util.List;

/**
 * Generisches Interface für Pipeline-Stufen.
 * @param <I> Input Typ
 * @param <O> Output Typ
 */
public interface StageHandler<I, O> {
    /**
     * Verarbeitet ein Item.
     * @param input Das Eingabe-Objekt
     * @return Das Ergebnis (oder null bei Fehler)
     * @throws Exception Wenn etwas schiefgeht
     */
    O process(I input) throws Exception;

    /**
     * Batch-Verarbeitung für Uploads (optional).
     */
    default void processBatch(List<I> batch) throws Exception {
        for (I item : batch) process(item);
    }
}