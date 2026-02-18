package com.framework.core.media;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Interface for media transcoding operations.
 * Allows decoupling between plugins (e.g. Dashboard and CoreMedia).
 */
public interface MediaTranscoder {

    /**
     * Processes a media file (image or video), applying watermarks, resizing, or
     * splitting as configured.
     *
     * @param input         The input file.
     * @param forceReencode Whether to force re-encoding even if not strictly
     *                      necessary.
     * @return A list of processed files (e.g. split parts), or the original file if
     *         no processing was needed.
     */
    List<File> processMedia(File input, boolean forceReencode);

    /**
     * Generates or retrieves a thumbnail for a video file.
     *
     * @param video The video file.
     * @return The thumbnail file, or null if generation failed.
     */
    File getOrCreateThumbnail(File video);

    /**
     * Retrieves metadata for a video file (width, height, duration, rotation).
     *
     * @param input The video file.
     * @return A map of metadata properties.
     */
    Map<String, Object> getVideoMetadata(File input);
}
