package internal;

import com.framework.common.util.OsUtils;
import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

public class LocalBotServer {
    private static final Logger logger = LoggerFactory.getLogger(LocalBotServer.class);
    private Process process;
    private final File toolsDir;

    private static final String API_ID = "30838869";
    private static final String API_HASH = "a9078242c87e536061717aa50c567a1f";
    private static final int PORT = 8081;

    public LocalBotServer(Kernel kernel) {
        this.toolsDir = kernel.getToolsDir();
    }

    public void start() {
        String executablePath = OsUtils.getTelegramServerExecutable();
        File exe = new File(executablePath);

        if (!exe.exists()) {
            logger.info("Kein lokaler Telegram-Server gefunden ({}).", exe.getName());
            return;
        }

        killOrphans();

        if (isApiReachable()) {
            logger.warn("WARNUNG: Port {} ist bereits belegt! Ein alter Server läuft noch.", PORT);
            return;
        }

        new Thread(() -> {
            try {
                logger.info("Starte Local Bot API Server...");
                OsUtils.makeExecutable(exe);

                ProcessBuilder pb = new ProcessBuilder(
                        exe.getAbsolutePath(),
                        "--api-id", API_ID,
                        "--api-hash", API_HASH,
                        "--local",
                        "--http-port", String.valueOf(PORT),
                        "--verbosity", "2"
                );

                pb.directory(toolsDir);
                pb.redirectErrorStream(true);

                process = pb.start();

                // EXTENSIVE LOGGING: Alles lesen und an den Logger weiterreichen
                // Durch die Main-Klasse landet das dann im File.
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.info("[TG-Server] {}", line);
                    }
                }

                if (process.isAlive()) {
                    int exitCode = process.waitFor();
                    logger.warn("Server Prozess beendet (Code: {}).", exitCode);
                }

            } catch (Exception e) {
                logger.error("Fehler beim Starten des API Servers", e);
            }
        }, "TG-Server-Watchdog").start();
    }

    public void restart() {
        logger.warn("🔄 Führe Neustart des Local Bot API Servers durch...");
        stop();
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        start();
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try { Thread.sleep(1000); if (process.isAlive()) process.destroyForcibly(); } catch (Exception e) {}
            logger.info("Local Bot API Server gestoppt.");
        }
    }

    public boolean isProcessRunning() { return process != null && process.isAlive(); }

    public boolean isApiReachable() {
        try (Socket ignored = new Socket("localhost", PORT)) { return true; } catch (IOException e) { return false; }
    }

    public String getApiUrl() { return "http://localhost:" + PORT + "/bot%s/%s"; }

    private void killOrphans() {
        try {
            ProcessHandle.allProcesses()
                    .filter(p -> p.info().command().isPresent())
                    .filter(p -> p.info().command().get().toLowerCase().contains("telegram-bot-api"))
                    .forEach(p -> {
                        if (process == null || p.pid() != process.pid()) p.destroy();
                    });
        } catch (Exception e) {}
    }
}