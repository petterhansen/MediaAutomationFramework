package com.plugins.coremedia;

import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DownloadService {
    private static final Logger logger = LoggerFactory.getLogger(DownloadService.class);
    private final File tempDir;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    public DownloadService(Kernel kernel) {
        String t = kernel.getConfigManager().getConfig().tempDir;
        this.tempDir = new File(t != null ? t : "temp");
        if (!tempDir.exists()) tempDir.mkdirs();
    }

    public DownloadService(File tempDir) {
        this.tempDir = tempDir;
    }

    public File downloadFile(String url, String folderName, String fileName, Map<String, String> headers) {
        File targetDir = new File("media_cache");
        if (folderName != null && !folderName.isEmpty()) {
            targetDir = new File(targetDir, folderName.replaceAll("[^a-zA-Z0-9._-]", "_"));
        }
        if (!targetDir.exists()) targetDir.mkdirs();

        File targetFile = new File(targetDir, fileName);
        if (targetFile.exists() && targetFile.length() > 0) return targetFile;

        File ariaExe = new File("tools/aria2c.exe");
        boolean ariaAvailable = ariaExe.exists();

        if (ariaAvailable) {
            boolean success = performAriaDownload(ariaExe, url, targetDir, fileName, headers);
            if (success) return targetFile;

            // LOGGING UPDATE: URL anzeigen
            logger.warn("⚠️ Aria2 fehlgeschlagen für URL: {} | Versuche Java-Fallback für: {}", url, fileName);
        } else {
            logger.warn("⚠️ Aria2 nicht gefunden. Nutze Java-Downloader.");
        }

        // --- FALLBACK ---
        return performJavaDownload(url, targetFile, headers);
    }

    private boolean performAriaDownload(File exe, String url, File dir, String file, Map<String, String> headers) {
        try {
            List<String> command = new ArrayList<>();
            command.add(exe.getAbsolutePath());
            command.add("--dir=" + dir.getAbsolutePath());
            command.add("--out=" + file);
            command.add("--split=4");
            command.add("--max-connection-per-server=4");
            command.add("--allow-overwrite=true");
            command.add("--check-certificate=false");
            command.add("--user-agent=" + USER_AGENT);

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    command.add("--header=" + e.getKey() + ": " + e.getValue());
                }
            }
            command.add(url);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File("."));
            pb.redirectErrorStream(true);

            Process p = pb.start();
            // Output konsumieren (wichtig gegen Hänger)
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // Optional: Aria2 Output bei Fehlern auch loggen
                    if (line.toLowerCase().contains("error")) logger.debug("Aria2 Output: " + line);
                }
            }

            return p.waitFor() == 0 && new File(dir, file).exists();
        } catch (Exception e) {
            logger.error("Aria2 Fail", e);
            return false;
        }
    }

    private File performJavaDownload(String urlStr, File targetFile, Map<String, String> headers) {
        try {
            // LOGGING UPDATE: URL anzeigen
            logger.info("⬇️ Starte Java Download: {} | URL: {}", targetFile.getName(), urlStr);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", USER_AGENT);

            if (headers != null) {
                if (headers.containsKey("Referer")) conn.setRequestProperty("Referer", headers.get("Referer"));
                if (headers.containsKey("Cookie")) conn.setRequestProperty("Cookie", headers.get("Cookie"));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                // LOGGING UPDATE: URL bei Fehler anzeigen
                logger.error("❌ Java DL HTTP Error: {} | URL: {}", responseCode, urlStr);
                return null;
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            }
            return targetFile;
        } catch (Exception e) {
            logger.error("Java DL Exception: {} | URL: {}", e.getMessage(), urlStr);
            return null;
        }
    }
}