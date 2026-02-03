package com.plugins.coremedia;

import com.framework.common.util.OsUtils;
import com.framework.core.config.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class TranscoderService {
    private static final Logger logger = LoggerFactory.getLogger(TranscoderService.class);

    private final Configuration config;
    private final String pluginName = "CoreMedia";

    private String cachedFontPath = null;

    public TranscoderService(com.framework.core.Kernel kernel) {
        this.config = kernel.getConfigManager().getConfig();
        logger.info("TranscoderService initialized (Hot-Reload Ready).");
    }

    // Dynamic Settings
    private boolean isWatermarkEnabled() {
        return Boolean.parseBoolean(config.getPluginSetting(pluginName, "watermark_enabled", "false"));
    }

    private String getWatermarkText() {
        return config.getPluginSetting(pluginName, "watermark_text", "Media Automation Framework");
    }

    private int getWatermarkSize() {
        return Integer.parseInt(config.getPluginSetting(pluginName, "watermark_size", "24"));
    }

    private double getWatermarkOpacity() {
        return Double.parseDouble(config.getPluginSetting(pluginName, "watermark_opacity", "0.5"));
    }

    private long getSplitThreshold() {
        return Long.parseLong(config.getPluginSetting(pluginName, "split_threshold_mb", "1999")) * 1024 * 1024;
    }

    public List<File> processMedia(File input, boolean forceReencode) {
        if (input == null || !input.exists())
            return new ArrayList<>();

        String name = input.getName().toLowerCase();
        boolean useWatermark = isWatermarkEnabled();

        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) {
            if (useWatermark) {
                File processed = processImage(input);
                if (processed != null && processed.exists()) {
                    input.delete();
                    return List.of(processed);
                }
            }
            return List.of(input);
        }

        if (isVideoFile(input) || name.endsWith(".gif")) {
            return processVideo(input, forceReencode);
        }

        return List.of(input);
    }

    // --- BILDER ---
    private File processImage(File input) {
        String ext = input.getName().substring(input.getName().lastIndexOf("."));
        File out = new File(input.getParent(), "wm_" + System.currentTimeMillis() + ext);
        List<String> cmd = new ArrayList<>();
        cmd.add(OsUtils.getFfmpegCommand());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(input.getAbsolutePath());

        String fontPath = getFontPath();
        if (fontPath != null) {
            cmd.add("-vf");
            // MediaChat-style watermark for images: shadow instead of box, bottom-right
            // Use same size as video (h/35) or slightly smaller (h/40) to avoid being huge on high-res portrait images
            cmd.add(String.format(Locale.US,
                    "drawtext=text='%s':fontfile='%s':fontcolor=white@0.7:fontsize=h/40:x=w-tw-(h/50):y=h-th-(h/50):shadowcolor=black@0.6:shadowx=4:shadowy=4",
                    getWatermarkText(), fontPath));
        }

        cmd.add(out.getAbsolutePath());
        return executeFFmpeg(cmd, "Image-WM") && out.exists() ? out : null;
    }

    // --- VIDEOS ---
    public List<File> processVideo(File input, boolean forceReencode) {
        if (input == null || !input.exists())
            return new ArrayList<>();

        String name = input.getName().toLowerCase();
        boolean isGif = name.endsWith(".gif");
        boolean useWatermark = isWatermarkEnabled();

        try {
            VideoProbeResult probe = probeVideo(input);
            boolean isValidVideo = (probe != null && probe.width > 0 && probe.height > 0);

            // FALLBACK: Wenn Probe fehlschlägt, Header manuell prüfen
            if (!isValidVideo) {
                if (hasVideoHeader(input)) {
                    logger.warn(
                            "⚠️ Probe fehlgeschlagen für {}, aber Datei-Header sieht valide aus. Versuche blinde Verarbeitung.",
                            input.getName());
                    // Wir tun so, als wäre alles ok, nutzen aber Defaults (keine Vorschau, kein
                    // Split)
                    probe = new VideoProbeResult(1920, 1080, 0);
                } else {
                    // Wirklich kaputt (z.B. HTML Error Page)
                    String contentSnippet = readHeaderSnippet(input);
                    logger.error("❌ DATEI KORRUPT: {} (Size: {} bytes). Inhalt: '{}'. Lösche Datei...",
                            input.getName(), input.length(), contentSnippet);
                    input.delete();
                    return new ArrayList<>();
                }
            }

            if (isGif) {
                File mp4 = convertGifToMp4(input, useWatermark);
                if (mp4 != null && mp4.exists()) {
                    input.delete();
                    return List.of(mp4);
                }
                return List.of(input);
            }

            long size = input.length();
            long splitThreshold = getSplitThreshold();
            boolean needsSplit = size > splitThreshold;
            File preview = null;

            // Preview nur erstellen, wenn wir die Dauer kennen und > 60s
            if (probe.duration > 60) {
                preview = generateRobustPreview(input, probe.duration);
            }

            List<File> resultFiles = new ArrayList<>();

            if (needsSplit || forceReencode || useWatermark) {
                List<File> processedParts = transcodeAndSplit(input, useWatermark, needsSplit);
                if (!processedParts.isEmpty()) {
                    input.delete();
                    resultFiles.addAll(processedParts);
                } else {
                    resultFiles.add(input);
                }
            } else {
                resultFiles.add(input);
            }

            if (!resultFiles.isEmpty()) {
                getOrCreateThumbnail(resultFiles.get(0));
            }

            if (preview != null && preview.exists()) {
                resultFiles.add(0, preview);
            }

            return resultFiles;

        } catch (Exception e) {
            logger.error("Error processing file: {}", input.getName(), e);
        }
        return List.of(input);
    }

    // --- MANUELLE HEADER PRÜFUNG (Fallback) ---
    private boolean hasVideoHeader(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] header = new byte[32];
            int read = fis.read(header);
            if (read < 4)
                return false;

            String hStr = new String(header, StandardCharsets.ISO_8859_1);

            // MP4/MOV Signaturen
            if (hStr.contains("ftyp") || hStr.contains("moov"))
                return true;
            // MKV/WebM (EBML)
            if (header[0] == 0x1A && header[1] == 0x45 && header[2] == (byte) 0xDF && header[3] == (byte) 0xA3)
                return true;

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String readHeaderSnippet(File f) {
        try (FileReader fr = new FileReader(f)) {
            char[] buf = new char[100];
            int read = fr.read(buf);
            if (read > 0)
                return new String(buf, 0, read).replaceAll("[\\r\\n]+", " ");
        } catch (Exception e) {
        }
        return "read_error";
    }

    // --- CONVERSION & TOOLS ---

    private File convertGifToMp4(File input, boolean useWm) {
        File out = new File(input.getParent(), input.getName().replaceFirst("[.][^.]+$", "") + "_v.mp4");
        List<String> cmd = new ArrayList<>();
        cmd.add(OsUtils.getFfmpegCommand());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(input.getAbsolutePath());

        String filter = "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p";
        if (useWm) {
            String wmFilter = getDrawTextFilter();
            if (wmFilter != null)
                filter += "," + wmFilter;
        }
        cmd.add("-vf");
        cmd.add(filter);
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("slow");
        cmd.add("-crf");
        cmd.add("22");
        cmd.add("-movflags");
        cmd.add("+faststart");

        cmd.add(out.getAbsolutePath());
        return executeFFmpeg(cmd, "GIF-Convert") && out.exists() ? out : null;
    }

    private File generateRobustPreview(File input, double duration) {
        if (duration <= 0)
            return null; // Ohne Dauer keine Preview
        int clips = 9;
        int clipLen = 3;
        double startOffset = Math.max(2, duration * 0.05);
        double usableDuration = duration - startOffset - clipLen;
        if (usableDuration <= 0)
            return null;

        double step = usableDuration / (clips - 1);
        String baseName = input.getName().replaceFirst("[.][^.]+$", "");
        File tempDir = new File(input.getParentFile(), "preview_temp_" + System.currentTimeMillis());
        if (!tempDir.mkdirs())
            return null;

        try {
            List<File> segments = new ArrayList<>();
            String ffmpeg = OsUtils.getFfmpegCommand();
            logger.info("🎬 Erstelle Robust-Preview für {}...", baseName);

            for (int i = 0; i < clips; i++) {
                double startTime = startOffset + (i * step);
                File segFile = new File(tempDir, String.format("seg_%03d.mp4", i));

                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpeg);
                cmd.add("-y");
                cmd.add("-ss");
                cmd.add(String.format(Locale.US, "%.2f", startTime));
                cmd.add("-t");
                cmd.add(String.valueOf(clipLen));
                cmd.add("-i");
                cmd.add(input.getAbsolutePath());
                cmd.add("-vf");
                cmd.add("scale=640:-2,setsar=1");
                cmd.add("-c:v");
                cmd.add("libx264");
                cmd.add("-preset");
                cmd.add("veryfast");
                cmd.add("-c:a");
                cmd.add("aac");
                cmd.add(segFile.getAbsolutePath());

                if (executeFFmpeg(cmd, "Preview-Seg-" + i) && segFile.exists()) {
                    segments.add(segFile);
                }
            }

            if (segments.size() < clips) {
                deleteRecursive(tempDir);
                return null;
            }

            File concatList = new File(tempDir, "list.txt");
            try (PrintWriter pw = new PrintWriter(concatList)) {
                for (File f : segments)
                    pw.println("file '" + f.getAbsolutePath() + "'");
            }

            File outVideo = new File(input.getParent(), baseName + "_preview.mp4");
            List<String> concatCmd = Arrays.asList(
                    ffmpeg, "-y", "-f", "concat", "-safe", "0",
                    "-i", concatList.getAbsolutePath(),
                    "-c", "copy",
                    outVideo.getAbsolutePath());

            if (executeFFmpeg(concatCmd, "Preview-Concat") && outVideo.exists()) {
                createPreviewThumbnail(outVideo, ffmpeg);
                deleteRecursive(tempDir);
                return outVideo;
            }

        } catch (Exception e) {
            logger.error("Preview failed", e);
        }
        deleteRecursive(tempDir);
        return null;
    }

    private void createPreviewThumbnail(File video, String ffmpeg) {
        try {
            File thumb = new File(video.getAbsolutePath() + ".thumb.jpg");
            List<String> cmd = Arrays.asList(
                    ffmpeg, "-y", "-i", video.getAbsolutePath(),
                    "-vf", "fps=1/3,tile=3x3,scale=320:-1",
                    "-frames:v", "1", "-q:v", "3",
                    thumb.getAbsolutePath());
            executeFFmpeg(cmd, "Preview-Thumb");
        } catch (Exception ignored) {
        }
    }

    private List<File> transcodeAndSplit(File input, boolean useWm, boolean shouldSplit) {
        File outDir = input.getParentFile();
        String baseName = input.getName().replaceFirst("[.][^.]+$", "");
        List<File> parts = new ArrayList<>();

        List<String> cmd = new ArrayList<>();
        cmd.add(OsUtils.getFfmpegCommand());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(input.getAbsolutePath());

        String filter = "scale='min(1920,iw)':-2,pad=ceil(iw/2)*2:ceil(ih/2)*2,setsar=1,format=yuv420p";
        if (useWm) {
            String wmFilter = getDrawTextFilter();
            if (wmFilter != null)
                filter += "," + wmFilter;
        }

        cmd.add("-vf");
        cmd.add(filter);
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-crf");
        cmd.add("23");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-map_metadata");
        cmd.add("-1");
        cmd.add("-movflags");
        cmd.add("+faststart");

        if (shouldSplit) {
            cmd.add("-f");
            cmd.add("segment");
            cmd.add("-segment_time");
            cmd.add("900");
            cmd.add("-reset_timestamps");
            cmd.add("1");
            cmd.add(new File(outDir, baseName + "_part%03d.mp4").getAbsolutePath());
        } else {
            cmd.add(new File(outDir, baseName + "_processed.mp4").getAbsolutePath());
        }

        if (executeFFmpeg(cmd, "Transcode")) {
            if (shouldSplit) {
                File[] generated = outDir
                        .listFiles((dir, name) -> name.startsWith(baseName + "_part") && name.endsWith(".mp4"));
                if (generated != null) {
                    Arrays.sort(generated, Comparator.comparing(File::getName));
                    Collections.addAll(parts, generated);
                }
            } else {
                File out = new File(outDir, baseName + "_processed.mp4");
                if (out.exists())
                    parts.add(out);
            }
        }
        return parts;
    }

    public File getOrCreateThumbnail(File video) {
        if (video == null || !video.exists())
            return null;
        File thumb = new File(video.getAbsolutePath() + ".thumb.jpg");
        if (thumb.exists() && thumb.length() > 0)
            return thumb;

        try {
            List<String> cmd = Arrays.asList(
                    OsUtils.getFfmpegCommand(), "-y", "-i", video.getAbsolutePath(),
                    "-ss", "00:00:05", "-vframes", "1", "-q:v", "5", "-vf", "scale=320:-1",
                    thumb.getAbsolutePath());
            executeFFmpeg(cmd, "Thumbnail");
            if (thumb.exists())
                return thumb;
        } catch (Exception e) {
        }
        return null;
    }

    // --- HELPER & EXECUTOR ---

    private VideoProbeResult probeVideo(File input) {
        // Zuerst prüfen, ob ffprobe überhaupt existiert
        String ffprobe = OsUtils.getFfmpegCommand().replace("ffmpeg", "ffprobe");
        if (OsUtils.isWindows() && !ffprobe.endsWith(".exe"))
            ffprobe += ".exe";

        // Simpler Check ob Command ausführbar wäre (nur falls File Check möglich)
        // Hier vertrauen wir drauf und fangen Exceptions ab.

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobe,
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height,duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    input.getAbsolutePath());
            // ErrorStream redirecten, damit er nicht blockiert
            pb.redirectErrorStream(true);

            Process p = pb.start();

            int w = 0, h = 0;
            double d = 0;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty())
                        continue;

                    // Versuche simple Zahl zu parsen
                    try {
                        if (line.contains(".")) {
                            double val = Double.parseDouble(line);
                            if (d == 0)
                                d = val; // Duration ist meist Double
                        } else {
                            int val = Integer.parseInt(line);
                            if (w == 0)
                                w = val;
                            else if (h == 0)
                                h = val;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            // Wenn wir Breite und Höhe haben, ist es ein Erfolg
            if (w > 0 && h > 0)
                return new VideoProbeResult(w, h, d);

        } catch (Exception e) {
            logger.debug("Probe failed: {}", e.getMessage());
        }
        return null;
    }

    private record VideoProbeResult(int width, int height, double duration) {
    }

    private boolean executeFFmpeg(List<String> cmd, String taskName) {
        try {
            logger.debug("[{}] Starte: {}", taskName, String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("frame=") || line.startsWith("size=")) {
                        logger.trace("[{}] {}", taskName, line);
                    } else if (line.toLowerCase().contains("error") || line.toLowerCase().contains("warning")) {
                        logger.warn("[{}] {}", taskName, line);
                    } else {
                        logger.debug("[{}] {}", taskName, line);
                    }
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                logger.error("[{}] Fehlgeschlagen mit Exit Code {}", taskName, code);
                return false;
            }
            logger.debug("[{}] Erfolgreich beendet.", taskName);
            return true;

        } catch (Exception e) {
            logger.error("[{}] Exception: {}", taskName, e.getMessage());
            return false;
        }
    }

    private String getDrawTextFilter() {
        String fp = getFontPath();
        if (fp == null)
            return null;

        // MediaChat-style watermark: bottom-right position with shadow
        // Format: white@0.7 text, h/35 font size, positioned at bottom-right with
        // margin h/50
        // Shadow: black@0.6 with 2px offset
        return String.format(Locale.US,
                "drawtext=text='%s':fontfile='%s':fontcolor=white@0.7:fontsize=h/35:x=w-tw-(h/50):y=h-th-(h/50):shadowcolor=black@0.6:shadowx=2:shadowy=2",
                getWatermarkText(), fp);
    }

    private String getFontPath() {
        if (cachedFontPath != null)
            return cachedFontPath;
        File toolFont = new File("tools/font.ttf");
        if (toolFont.exists()) {
            cachedFontPath = escapePath(toolFont.getAbsolutePath());
            return cachedFontPath;
        }
        if (OsUtils.isWindows()) {
            File winFont = new File("C:/Windows/Fonts/arial.ttf");
            if (winFont.exists()) {
                cachedFontPath = escapePath(winFont.getAbsolutePath());
                return cachedFontPath;
            }
        }
        return null; // Kein Font -> kein Watermark
    }

    private String escapePath(String path) {
        if (OsUtils.isWindows()) {
            return path.replace("\\", "/").replace(":", "\\:");
        }
        return path;
    }

    private boolean isVideoFile(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".m4v") || n.endsWith(".webm")
                || n.endsWith(".mkv");
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children)
                    deleteRecursive(c);
            }
        }
        f.delete();
    }
}