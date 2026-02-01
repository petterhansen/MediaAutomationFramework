package com.framework;

import com.framework.core.Kernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Main {
    // Logger erst initialisieren NACHDEM Streams umgebogen wurden (Trick 17)
    private static Logger logger;

    public static void main(String[] args) {
        setupGlobalLogging();

        logger = LoggerFactory.getLogger(Main.class);
        logger.info("🚀 Starting MediaAutomationFramework...");
        logger.info("📄 Log File: logs/latest.log (und session-*.log)");

        try {
            Kernel.getInstance().start();
            logger.info("Service running. Joining main thread.");
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.warn("Main thread interrupted. Exiting...");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("CRITICAL FAILURE during startup", e);
            e.printStackTrace(); // Sicherheitshalber auch direkt printen
            System.exit(1);
        }
    }

    /**
     * Leitet System.out und System.err in Dateien um, BEVOR irgendwas anderes passiert.
     */
    private static void setupGlobalLogging() {
        try {
            File logDir = new File("logs");
            if (!logDir.exists()) logDir.mkdirs();

            String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File sessionLog = new File(logDir, "session-" + timeStamp + ".log");
            File latestLog = new File(logDir, "latest.log");

            // Streams erstellen
            FileOutputStream sessionStream = new FileOutputStream(sessionLog);
            FileOutputStream latestStream = new FileOutputStream(latestLog); // Überschreibt latest.log

            // Multi-Writer: Konsole + SessionFile + LatestFile
            MultiOutputStream multiOut = new MultiOutputStream(System.out, sessionStream, latestStream);
            MultiOutputStream multiErr = new MultiOutputStream(System.err, sessionStream, latestStream);

            // Umleiten
            System.setOut(new PrintStream(multiOut, true, "UTF-8"));
            System.setErr(new PrintStream(multiErr, true, "UTF-8"));

        } catch (Exception e) {
            System.err.println("FATAL: Konnte Logging nicht initialisieren: " + e.getMessage());
        }
    }

    // Helper Klasse um Output an mehrere Ziele zu senden (Tee-Prinzip)
    static class MultiOutputStream extends OutputStream {
        private final OutputStream[] streams;

        public MultiOutputStream(OutputStream... streams) {
            this.streams = streams;
        }

        @Override
        public void write(int b) throws IOException {
            for (OutputStream s : streams) s.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (OutputStream s : streams) s.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            for (OutputStream s : streams) s.flush();
        }

        @Override
        public void close() throws IOException {
            for (OutputStream s : streams) s.close();
        }
    }
}