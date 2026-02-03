package com.plugins.downloadcmd;

/**
 * Data Transfer Object for download requests
 */
public record DownloadRequest(
    int amount,         // Number of items to download
    String source,      // "coomer", "kemono", "youtube", "booru", "browser", or null for auto
    String query,       // Creator name, tags, or URL
    String filetype,    // "vid", "img", or null
    String service      // "onlyfans", "patreon", "fansly", or null
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
        return sb.toString();
    }
}
