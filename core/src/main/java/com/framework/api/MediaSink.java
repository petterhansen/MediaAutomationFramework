package com.framework.api;

import com.framework.core.pipeline.PipelineItem;
import com.framework.core.pipeline.StageHandler;

/**
 * Interface für Ausgabemodule (Telegram, Discord, LocalDisk).
 */
public interface MediaSink extends StageHandler<PipelineItem, Void> {
    // StageHandler definiert bereits: Void process(PipelineItem item)
}