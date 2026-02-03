package com.framework.core.pipeline;

import java.io.File;
import java.util.List;

/**
 * Hook interface for plugins to extend pipeline behavior.
 * All methods have default empty implementations, so plugins
 * only need to override the hooks they care about.
 */
public interface PipelineHook {

    /**
     * Called before an item enters the download stage
     */
    default void beforeDownload(PipelineItem item) {
    }

    /**
     * Called after successful download
     */
    default void afterDownload(PipelineItem item, File result) {
    }

    /**
     * Called before an item enters the processing stage
     */
    default void beforeProcessing(PipelineItem item) {
    }

    /**
     * Called after successful processing
     */
    default void afterProcessing(PipelineItem item, List<File> results) {
    }

    /**
     * Called before an item enters the upload stage
     */
    default void beforeUpload(PipelineItem item) {
    }

    /**
     * Called after successful upload
     */
    default void afterUpload(PipelineItem item) {
    }

    /**
     * Called when an error occurs in any pipeline stage
     * 
     * @param item  The pipeline item that failed
     * @param e     The exception that occurred
     * @param stage The stage where the error occurred ("download", "processing", or
     *              "upload")
     */
    default void onError(PipelineItem item, Exception e, String stage) {
    }
}
