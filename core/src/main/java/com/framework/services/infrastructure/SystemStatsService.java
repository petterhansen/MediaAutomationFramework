package com.framework.services.infrastructure;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Ersetzt die History-Logik aus src/RequestHandler.java
 * Sammelt System-Metriken (CPU, RAM) für das Dashboard.
 */
public class SystemStatsService {
    private static final Logger logger = LoggerFactory.getLogger(SystemStatsService.class);
    private final LinkedList<Double> historyCpu = new LinkedList<>();
    private final LinkedList<Long> historyRam = new LinkedList<>();
    private static final int MAX_HISTORY = 60;
    private final Timer timer;

    public SystemStatsService() {
        this.timer = new Timer("SystemMonitor", true);
    }

    public void start() {
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                update();
            }
        }, 1000, 1000);
    }

    public void stop() {
        timer.cancel();
    }

    private void update() {
        synchronized (historyCpu) {
            historyCpu.add(getCpuLoad());
            if (historyCpu.size() > MAX_HISTORY) historyCpu.removeFirst();

            long usedRam = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
            historyRam.add(usedRam);
            if (historyRam.size() > MAX_HISTORY) historyRam.removeFirst();
        }
    }

    public JsonObject getHistoryJson() {
        JsonObject root = new JsonObject();
        synchronized (historyCpu) {
            root.add("cpu", new Gson().toJsonTree(historyCpu));
            root.add("ram", new Gson().toJsonTree(historyRam));
        }
        return root;
    }

    // Unterdrückt Warnung für veraltete Methode in neueren JDKs
    @SuppressWarnings("deprecation")
    private double getCpuLoad() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad() * 100;
        }
        return 0;
    }
}