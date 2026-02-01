package com.framework.common.model;

import java.util.List;

/**
 * Vereint BooruPost, KemonoModels.Post und CoomerModels.Post.
 *
 */
public record GenericMediaItem(
        String id,
        String sourceName,    // z.B. "kemono", "r34"
        String service,       // z.B. "patreon", "user"
        String creatorName,
        String originalFileName,
        String fileUrl,
        List<String> tags,
        String description
) {}