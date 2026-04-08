package com.framework.common;

/**
 * Data Transfer Object for download requests.
 * Used by the /dl command and plugins handling sources.
 */
public record DownloadRequest(
    int amount,         // Number of items to download
    String source,      // Specific source or null for auto
    String query,       // Creator name, tags, or URL
    String filetype,    // Filter "vid", "img", or null
    String service,     // Service name filter or null
    String folder       // Optional folder/creator name for captions
) {
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(amount).append(" item(s) from ");
        if (source != null) {
            sb.append(source).append(":");
        } else {
            sb.append("auto:");
        }
        sb.append(query);
        if (filetype != null) {
            sb.append(" (").append(filetype).append(")");
        }
        if (service != null) {
            sb.append(" [").append(service).append("]");
        }
        if (folder != null) {
            sb.append(" to /").append(folder);
        }
        return sb.toString();
    }
}
