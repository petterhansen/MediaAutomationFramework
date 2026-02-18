package com.plugins.telegram.internal;

import com.framework.api.MediaSink;
import com.framework.core.pipeline.PipelineItem;
import com.plugins.telegram.internal.TelegramUploader.MediaItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TelegramSink implements MediaSink {
    private static final Logger logger = LoggerFactory.getLogger(TelegramSink.class);
    private final String targetChatId;
    private final String apiBase;
    private final TelegramUploader uploader;

    public TelegramSink(String botToken, String chatId, String apiUrlPattern) {
        this.targetChatId = chatId;
        this.apiBase = (apiUrlPattern != null ? apiUrlPattern : "http://localhost:8081/bot%s/%s");
        this.uploader = new TelegramUploader(botToken, this.apiBase);
    }

    @Override
    public Void process(PipelineItem item) throws Exception {
        List<File> files = item.getProcessedFiles();
        if (files == null || files.isEmpty())
            return null;

        // Determine target chat: use initiator if available, otherwise default config
        String actualTargetChatId = targetChatId;
        Integer actualThreadId = null;
        if (item.getParentTask() != null) {
            String initiator = item.getParentTask().getString("initiatorChatId");
            if (initiator != null && !initiator.isEmpty()) {
                actualTargetChatId = initiator;
                logger.debug("Routing upload to initiator: {}", actualTargetChatId);
            }

            // Topic support
            String threadIdStr = item.getParentTask().getString("initiatorThreadId");
            if (threadIdStr != null && !threadIdStr.isEmpty()) {
                try {
                    actualThreadId = Integer.parseInt(threadIdStr);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String caption = null;
        if (item.getParentTask() != null) {
            caption = item.getParentTask().getString("caption");
        }

        if (caption == null) {
            if (item.getMetadata().containsKey("creator")) {
                String creator = (String) item.getMetadata().get("creator");
                if (creator != null) {
                    String tag = creator.toLowerCase().trim().replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "");
                    caption = "#" + tag;
                }
            }
            if (caption == null)
                caption = "#" + item.getOriginalName();
        }

        // Probe all files to get metadata
        List<MediaItem> mediaItems = new ArrayList<>();
        for (File f : files) {
            mediaItems.add(probeFile(f));
        }

        // Album (MediaGroup) nur senden, wenn > 1 Datei UND <= 10
        // Album (MediaGroup) nur senden, wenn > 1 Datei UND <= 10
        if (files.size() > 1 && files.size() <= 10) {
            String finalChat = actualTargetChatId;
            Integer finalThread = actualThreadId;
            String finalCaption = caption;

            executeWithRetry(() -> uploader.uploadMediaGroup(mediaItems, finalCaption, finalChat, finalThread));
        } else {
            // Einzeln senden
            for (MediaItem mi : mediaItems) {
                String finalChat = actualTargetChatId;
                Integer finalThread = actualThreadId;
                String finalCaption = caption;

                if (mi.isVideo()) {
                    executeWithRetry(
                            () -> uploader.uploadVideo(mi.file(), mi.thumbnail(), finalCaption, finalChat, finalThread,
                                    mi.width(), mi.height(), mi.duration()));
                } else if (isImage(mi.file())) {
                    executeWithRetry(() -> uploader.uploadPhoto(mi.file(), finalCaption, finalChat, finalThread));
                } else {
                    executeWithRetry(() -> uploader.uploadDocument(mi.file(), finalCaption, finalChat, finalThread,
                            mi.thumbnail()));
                }

                try {
                    Thread.sleep(1000); // Rate Limit Schutz
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during batch waiting", ie);
                }
            }
        }
        return null;
    }

    private MediaItem probeFile(File input) {
        File thumb = new File(input.getAbsolutePath() + ".thumb.jpg");
        if (!thumb.exists())
            thumb = null;

        if (!isVideo(input)) {
            return new MediaItem(input, thumb, 0, 0, 0, false);
        }

        try {
            // COPY OF ROBUST PROBING LOGIC FROM TranscoderService / MediaProcessor
            String ffmpegCmd = "ffmpeg"; // Assume in PATH or handled by OS
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                ffmpegCmd = "ffmpeg.exe";
                // Try to find in tools/ if possible, but simplest is to assume PATH for now or
                // relative
                if (new File("tools/ffmpeg.exe").exists())
                    ffmpegCmd = "tools/ffmpeg.exe";
            }

            ProcessBuilder pb = new ProcessBuilder(ffmpegCmd, "-i", input.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            int width = 0;
            int height = 0;
            double duration = 0;

            // Regex from predecessor
            Pattern resPattern = Pattern.compile("Video:.*,\\s(\\d{2,5})x(\\d{2,5})");
            Pattern durPattern = Pattern.compile("Duration: (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2})");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher mRes = resPattern.matcher(line);
                    if (mRes.find()) {
                        width = Integer.parseInt(mRes.group(1));
                        height = Integer.parseInt(mRes.group(2));
                    }
                    Matcher mDur = durPattern.matcher(line);
                    if (mDur.find()) {
                        duration = (Integer.parseInt(mDur.group(1)) * 3600L) +
                                (Integer.parseInt(mDur.group(2)) * 60L) +
                                Integer.parseInt(mDur.group(3));
                        duration += Double.parseDouble("0." + mDur.group(4));
                    }

                    // Note: Rotation handling in upload is tricky.
                    // Telegram API doesn't support "rotation" param.
                    // Video MUST be baked (which TranscoderService now does) or we rely on client.
                    // But we DO need to perform probing to get SWAP DIMENSIONS if rotation is
                    // 90/270?
                    // Yes, but standard ffmpeg output usually shows "SAR/DAR" which we might parse.
                    // For now, we trust TranscoderService has fixed the file if needed.
                }
            }
            p.waitFor();

            if (width > 0 && height > 0) {
                return new MediaItem(input, thumb, width, height, (int) duration, true);
            }

        } catch (Exception e) {
            logger.warn("Probe failed for {}: {}", input.getName(), e.getMessage());
        }

        return new MediaItem(input, thumb, 0, 0, 0, true);
    }

    private boolean isVideo(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm");
    }

    private boolean isImage(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".bmp");
    }

    @FunctionalInterface
    private interface UploadAction {
        void run() throws IOException;
    }

    private void executeWithRetry(UploadAction action) throws IOException {
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                action.run();
                return; // Success
            } catch (TelegramRateLimitException e) {
                long waitSeconds = e.getRetryAfterSeconds();
                logger.warn("⚠️ Telegram Rate Limit (429). Waiting {}s before retry (attempt {}/{})",
                        waitSeconds, i + 1, maxRetries);
                try {
                    Thread.sleep(waitSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during rate limit wait", ie);
                }
            }
        }
        throw new IOException("Max retries exceeded for Telegram upload after rate limits.");
    }
}
