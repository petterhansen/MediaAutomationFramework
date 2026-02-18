package com.plugins.coremedia;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.framework.core.config.Configuration;
import com.framework.common.util.OsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CoreMediaPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(CoreMediaPlugin.class);
    private TranscoderService transcoderService;
    private DownloadService downloadService;

    @Override
    public String getName() {
        return "CoreMedia";
    }

    @Override
    public String getVersion() {
        return "1.2.1"; // Version erhöht
    }

    @Override
    public void onEnable(Kernel kernel) {
        setupDefaultSettings(kernel);

        this.transcoderService = new TranscoderService(kernel);
        this.downloadService = new DownloadService(kernel);

        kernel.registerService(TranscoderService.class, transcoderService);
        kernel.registerService(com.framework.core.media.MediaTranscoder.class, transcoderService);
        kernel.registerService(DownloadService.class, downloadService);

        // --- DOWNLOAD HANDLER (Standard HTTP) ---
        kernel.getPipelineManager().registerDownloadHandler(new com.framework.core.pipeline.StageHandler<>() {
            @Override
            public boolean supports(com.framework.core.pipeline.PipelineItem item) {
                // Fallback catch-all for http/https, BUT ignore yt-dlp items (handled by
                // YouTubePlugin)
                return !Boolean.TRUE.equals(item.getMetadata().get("yt_dlp")) &&
                        item.getSourceUrl().startsWith("http");
            }

            @Override
            public File process(com.framework.core.pipeline.PipelineItem item) throws Exception {
                Map<String, Object> meta = item.getMetadata();
                String folder = (String) meta.get("creator");
                if (folder == null)
                    folder = "downloads";

                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) meta.get("headers");

                return downloadService.downloadFile(item.getSourceUrl(), folder, item.getOriginalName(), headers);
            }
        });

        // --- LOCAL EXECUTOR ---
        LocalTaskExecutor localExecutor = new LocalTaskExecutor(kernel);
        kernel.getQueueManager().registerExecutor("LOCAL_BATCH", localExecutor);

        // --- LOCAL DOWNLOAD HANDLER ---
        kernel.getPipelineManager().registerDownloadHandler(new com.framework.core.pipeline.StageHandler<>() {
            @Override
            public boolean supports(com.framework.core.pipeline.PipelineItem item) {
                return item.getSourceUrl().startsWith("local://") ||
                        Boolean.TRUE.equals(item.getMetadata().get("is_local"));
            }

            @Override
            public File process(com.framework.core.pipeline.PipelineItem item) throws Exception {
                // If the file is already set (by LocalTaskExecutor), just return it
                if (item.getDownloadedFile() != null && item.getDownloadedFile().exists()) {
                    return item.getDownloadedFile();
                }

                // Otherwise try to find it based on URL: local://Folder/File.ext
                String path = item.getSourceUrl().replace("local://", "");
                File f = new File("media_cache", path);
                if (f.exists())
                    return f;

                throw new java.io.FileNotFoundException("Local file not found: " + path);
            }
        });

        // --- GEÄNDERTER PROCESSING HANDLER ---
        kernel.getPipelineManager().registerProcessingHandler(new com.framework.core.pipeline.StageHandler<>() {
            @Override
            public boolean supports(com.framework.core.pipeline.PipelineItem item) {
                return true; // Catch-all for now, or check mime types
            }

            @Override
            public List<File> process(com.framework.core.pipeline.PipelineItem item) throws Exception {
                File inputFile = item.getDownloadedFile();
                if (inputFile == null || !inputFile.exists())
                    return Collections.emptyList();

                String fileName = inputFile.getName().toLowerCase();

                // Use TranscoderService.processMedia which handles both images and videos
                logger.info("🎬 Verarbeite Datei: {}", fileName);

                List<File> processedFiles;
                if (fileName.contains("processed")) {
                    logger.info("⏩ Datei ist bereits verarbeitet, überspringe Transcoding: {}", fileName);
                    processedFiles = Collections.singletonList(inputFile);
                } else {
                    processedFiles = transcoderService.processMedia(inputFile, false);
                }

                // --- NEU: Metadaten für Telegram extrahieren ---
                if (processedFiles != null && !processedFiles.isEmpty()) {
                    File mainFile = processedFiles.get(0);
                    // Nur für Videodateien prüfen
                    String n = mainFile.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv")) {
                        Map<String, Object> meta = transcoderService.getVideoMetadata(mainFile);
                        if (!meta.isEmpty()) {
                            item.getMetadata().putAll(meta);
                            logger.info("📊 Video Metadata: {}", meta);
                        }
                    }
                }

                return processedFiles != null ? processedFiles : Collections.singletonList(inputFile);
            }
        });

        logger.info("🎬 CoreMedia aktiv: Pipeline mit Transcoder verbunden.");
    }

    @Override
    public void onDisable() {
        // Ressourcen freigeben
    }

    private void setupDefaultSettings(Kernel kernel) {
        Configuration config = kernel.getConfigManager().getConfig();
        String pluginName = getName();

        boolean dirty = false;

        if (config.getPluginSetting(pluginName, "ffmpeg_path", "").isEmpty()) {
            String defaultPath = OsUtils.isWindows() ? "tools/ffmpeg.exe" : "/usr/bin/ffmpeg";
            config.setPluginSetting(pluginName, "ffmpeg_path", defaultPath);
            dirty = true;
        }

        if (config.getPluginSetting(pluginName, "ffprobe_path", "").isEmpty()) {
            String defaultPath = OsUtils.isWindows() ? "tools/ffprobe.exe" : "/usr/bin/ffprobe";
            config.setPluginSetting(pluginName, "ffprobe_path", defaultPath);
            dirty = true;
        }

        if (config.getPluginSetting(pluginName, "use_gpu", "").isEmpty()) {
            config.setPluginSetting(pluginName, "use_gpu", "false");
            dirty = true;
        }

        if (config.getPluginSetting(pluginName, "preset", "").isEmpty()) {
            config.setPluginSetting(pluginName, "preset", "fast");
            dirty = true;
        }

        if (config.getPluginSetting(pluginName, "crf", "").isEmpty()) {
            config.setPluginSetting(pluginName, "crf", "23");
            dirty = true;
        }

        // NEU: Wasserzeichen Einstellungen
        if (config.getPluginSetting(pluginName, "watermark_enabled", "").isEmpty()) {
            config.setPluginSetting(pluginName, "watermark_enabled", "false");
            dirty = true;
        }
        if (config.getPluginSetting(pluginName, "watermark_text", "").isEmpty()) {
            config.setPluginSetting(pluginName, "watermark_text", "Media Automation Framework");
            dirty = true;
        }
        if (config.getPluginSetting(pluginName, "watermark_size", "").isEmpty()) {
            config.setPluginSetting(pluginName, "watermark_size", "24");
            dirty = true;
        }
        if (config.getPluginSetting(pluginName, "watermark_opacity", "").isEmpty()) {
            config.setPluginSetting(pluginName, "watermark_opacity", "0.5");
            dirty = true;
        }

        // Video split threshold (in MB)
        if (config.getPluginSetting(pluginName, "split_threshold_mb", "").isEmpty()) {
            config.setPluginSetting(pluginName, "split_threshold_mb", "1999");
            dirty = true;
        }

        if (dirty) {
            kernel.getConfigManager().saveConfig();
        }
    }

    // Old yt-dlp code removed (moved to YouTubePlugin)
}