package com.plugins.dashboard.internal;

import com.framework.core.Kernel;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles media streaming and browsing for guest users
 * Provides:
 * - /api/media/browse - Directory listing
 * - /api/media/stream - Video streaming with HTTP range support
 * - /api/media/view - Image/document serving
 * - /api/media/thumbnail - Thumbnail serving
 */
public class MediaStreamHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(MediaStreamHandler.class);
    private final Kernel kernel;
    private final Gson gson = new Gson();
    private final File mediaRoot;

    public MediaStreamHandler(Kernel kernel) {
        this.kernel = kernel;
        this.mediaRoot = new File("media_cache").getAbsoluteFile();
        if (!mediaRoot.exists()) {
            mediaRoot.mkdirs();
        }
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        // Check authentication
        String token = getToken(ex);
        if (token == null || !kernel.getAuthManager().isValidToken(token)) {
            sendJson(ex, 401, Map.of("error", "Unauthorized"));
            return;
        }

        try {
            if (path.startsWith("/api/media/browse")) {
                handleBrowse(ex);
            } else if (path.startsWith("/api/media/stream")) {
                handleStream(ex);
            } else if (path.startsWith("/api/media/view")) {
                handleView(ex);
            } else if (path.startsWith("/api/media/thumbnail")) {
                handleThumbnail(ex);
            } else {
                sendJson(ex, 404, Map.of("error", "Not found"));
            }
        } catch (Exception e) {
            logger.error("Error handling media request", e);
            sendJson(ex, 500, Map.of("error", "Internal server error"));
        }
    }

    /**
     * Browse directory contents
     * GET /api/media/browse?path=creator/subfolder
     */
    private void handleBrowse(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String relativePath = getQueryParam(query, "path", "");

        File targetDir = resolveSafePath(relativePath);
        if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory()) {
            sendJson(ex, 404, Map.of("error", "Directory not found"));
            return;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        File[] files = targetDir.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));

            for (File file : files) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", file.getName());
                item.put("type", file.isDirectory() ? "folder" : getFileType(file));
                item.put("size", file.length());
                item.put("modified", file.lastModified());

                if (!file.isDirectory()) {
                    String relPath = getRelativePath(file);
                    item.put("path", relPath);

                    // Add thumbnail path if available
                    File thumb = getThumbnailFile(file);
                    if (thumb != null && thumb.exists()) {
                        item.put("thumbnail", getRelativePath(thumb));
                    }
                }

                items.add(item);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("path", relativePath);
        response.put("items", items);
        sendJson(ex, 200, response);
    }

    /**
     * Stream video with HTTP range support
     * GET /api/media/stream?file=creator/video.mp4
     */
    private void handleStream(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String filePath = getQueryParam(query, "file", null);

        if (filePath == null) {
            sendJson(ex, 400, Map.of("error", "Missing file parameter"));
            return;
        }

        File file = resolveSafePath(filePath);
        if (file == null || !file.exists() || !file.isFile()) {
            sendJson(ex, 404, Map.of("error", "File not found"));
            return;
        }

        // Get range header for video seeking
        String rangeHeader = ex.getRequestHeaders().getFirst("Range");
        long fileSize = file.length();

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            // Parse range: bytes=start-end
            String[] range = rangeHeader.substring(6).split("-");
            long start = Long.parseLong(range[0]);
            long end = range.length > 1 && !range[1].isEmpty() ? Long.parseLong(range[1]) : fileSize - 1;

            long contentLength = end - start + 1;

            ex.getResponseHeaders().set("Content-Type", getMimeType(file));
            ex.getResponseHeaders().set("Content-Range",
                    String.format("bytes %d-%d/%d", start, end, fileSize));
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.getResponseHeaders().set("Content-Length", String.valueOf(contentLength));
            ex.sendResponseHeaders(206, contentLength);

            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                    OutputStream os = ex.getResponseBody()) {
                raf.seek(start);
                byte[] buffer = new byte[8192];
                long remaining = contentLength;

                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = raf.read(buffer, 0, toRead);
                    if (read == -1)
                        break;
                    os.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        } else {
            // Full file
            ex.getResponseHeaders().set("Content-Type", getMimeType(file));
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.sendResponseHeaders(200, fileSize);

            try (FileInputStream fis = new FileInputStream(file);
                    OutputStream os = ex.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        }
    }

    /**
     * View image or document
     * GET /api/media/view?file=creator/image.jpg
     */
    private void handleView(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String filePath = getQueryParam(query, "file", null);

        if (filePath == null) {
            sendJson(ex, 400, Map.of("error", "Missing file parameter"));
            return;
        }

        File file = resolveSafePath(filePath);
        if (file == null || !file.exists() || !file.isFile()) {
            sendJson(ex, 404, Map.of("error", "File not found"));
            return;
        }

        ex.getResponseHeaders().set("Content-Type", getMimeType(file));
        ex.sendResponseHeaders(200, file.length());

        try (FileInputStream fis = new FileInputStream(file);
                OutputStream os = ex.getResponseBody()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }
    }

    /**
     * Serve thumbnail
     * GET /api/media/thumbnail?file=creator/video.mp4
     */
    private void handleThumbnail(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String filePath = getQueryParam(query, "file", null);

        if (filePath == null) {
            sendJson(ex, 400, Map.of("error", "Missing file parameter"));
            return;
        }

        File file = resolveSafePath(filePath);
        if (file == null || !file.exists()) {
            sendJson(ex, 404, Map.of("error", "File not found"));
            return;
        }

        File thumb = getThumbnailFile(file);
        if (thumb == null || !thumb.exists()) {
            sendJson(ex, 404, Map.of("error", "Thumbnail not found"));
            return;
        }

        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
        ex.sendResponseHeaders(200, thumb.length());

        try (FileInputStream fis = new FileInputStream(thumb);
                OutputStream os = ex.getResponseBody()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }
    }

    // --- HELPER METHODS ---

    private String getToken(HttpExchange ex) {
        // First check X-MAF-Token header (used by viewer.html fetch requests)
        String token = ex.getRequestHeaders().getFirst("X-MAF-Token");
        if (token != null && !token.isEmpty()) {
            return token;
        }

        // Check query parameter (used by img/video/iframe tags)
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            String queryToken = getQueryParam(query, "token", null);
            if (queryToken != null && !queryToken.isEmpty()) {
                return queryToken;
            }
        }

        // Fallback to cookie-based authentication
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String c : cookie.split(";")) {
                String[] parts = c.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals("token")) {
                    return parts[1];
                }
            }
        }
        return null;
    }

    /**
     * Resolve path safely, preventing directory traversal
     */
    private File resolveSafePath(String relativePath) {
        try {
            File resolved = new File(mediaRoot, relativePath).getCanonicalFile();
            // Ensure it's within media_cache
            if (!resolved.getPath().startsWith(mediaRoot.getPath())) {
                logger.warn("Path traversal attempt blocked: {}", relativePath);
                return null;
            }
            return resolved;
        } catch (IOException e) {
            logger.error("Error resolving path: {}", relativePath, e);
            return null;
        }
    }

    private String getRelativePath(File file) {
        try {
            String fullPath = file.getCanonicalPath();
            String rootPath = mediaRoot.getCanonicalPath();
            if (fullPath.startsWith(rootPath)) {
                return fullPath.substring(rootPath.length() + 1).replace("\\", "/");
            }
        } catch (IOException e) {
            logger.error("Error getting relative path", e);
        }
        return file.getName();
    }

    private File getThumbnailFile(File mediaFile) {
        try {
            String hash = getMD5Hash(mediaFile.getAbsolutePath());
            return new File("media_cache/.thumbs/" + hash + ".jpg");
        } catch (Exception e) {
            return null;
        }
    }

    private String getMD5Hash(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private String getFileType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mkv") || name.endsWith(".mov")) {
            return "video";
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif")
                || name.endsWith(".webp")) {
            return "image";
        } else if (name.endsWith(".pdf")) {
            return "pdf";
        } else if (name.endsWith(".txt") || name.endsWith(".md")) {
            return "text";
        }
        return "file";
    }

    private String getMimeType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mp4"))
            return "video/mp4";
        if (name.endsWith(".webm"))
            return "video/webm";
        if (name.endsWith(".mkv"))
            return "video/x-matroska";
        if (name.endsWith(".mov"))
            return "video/quicktime";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg"))
            return "image/jpeg";
        if (name.endsWith(".png"))
            return "image/png";
        if (name.endsWith(".gif"))
            return "image/gif";
        if (name.endsWith(".webp"))
            return "image/webp";
        if (name.endsWith(".pdf"))
            return "application/pdf";
        if (name.endsWith(".txt"))
            return "text/plain";
        if (name.endsWith(".md"))
            return "text/markdown";
        return "application/octet-stream";
    }

    private String getQueryParam(String query, String param, String defaultValue) {
        if (query == null)
            return defaultValue;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(param)) {
                try {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private void sendJson(HttpExchange ex, int code, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }
}
