package com.framework.core.queue;

import com.framework.core.Kernel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class QueueManager {
    private static final Logger logger = LoggerFactory.getLogger(QueueManager.class);

    private final Kernel kernel;
    private final LinkedList<QueueTask> queue = new LinkedList<>();
    private final Map<String, TaskExecutor> executors = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final File queueFile;
    private final Gson gson;
    private Thread workerThread;

    public QueueManager(Kernel kernel) {
        this.kernel = kernel;
        this.queueFile = new File(kernel.getToolsDir(), "queue.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadQueue();
    }

    public void registerExecutor(String taskType, TaskExecutor executor) {
        executors.put(taskType, executor);
        logger.info("Executor registered for task type: {}", taskType);
    }

    public void addTask(QueueTask task) {
        synchronized (queue) {
            queue.offer(task);
            saveQueue();
            queue.notifyAll();
        }
        logger.info("Task added: {} (Type: {})", task.getId(), task.getType());
    }

    // --- Methoden für Plugins & Dashboard ---

    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Gibt die Queue als Liste von QueueTask-Objekten zurück.
     * Wird vom DashboardServer benötigt.
     */
    public List<QueueTask> getQueue() {
        return getQueueTasks();
    }

    /**
     * Interne Methode zum sicheren Abruf der Liste.
     */
    public List<QueueTask> getQueueTasks() {
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    /**
     * Gibt die Queue als formatierte String-Liste zurück.
     * Wird vom CoreCommandsPlugin (/queue Befehl) benötigt.
     */
    public List<String> getQueueList() {
        synchronized (queue) {
            List<String> list = new ArrayList<>();
            for (QueueTask task : queue) {
                // Holt den Namen sicher ab (QueueTask.getName() wurde in Schritt 3 hinzugefügt)
                String name = "Unbekannt";
                try { name = task.getName(); } catch (Exception e) { name = task.getType(); }

                String info = String.format("[%s] %s (ID: %s)",
                        task.getStatus() != null ? task.getStatus() : "WAITING",
                        name,
                        task.getId());
                list.add(info);
            }
            return list;
        }
    }
    // ----------------------------------------

    public void clearQueue() {
        synchronized (queue) {
            queue.clear();
            saveQueue();
        }
        logger.warn("Queue cleared by user request.");
    }

    public int getQueueSize() {
        synchronized (queue) {
            return queue.size();
        }
    }

    public void start() {
        if (running.getAndSet(true)) return;
        workerThread = new Thread(this::workerLoop, "QueueWorker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void stop() {
        running.set(false);
        if (workerThread != null) workerThread.interrupt();
    }

    public void setPaused(boolean isPaused) {
        this.paused.set(isPaused);
        if (!isPaused) {
            synchronized (queue) {
                queue.notifyAll();
            }
        }
        logger.info("Queue paused state: {}", isPaused);
    }

    private void workerLoop() {
        while (running.get()) {
            QueueTask task;

            synchronized (queue) {
                while ((queue.isEmpty() || paused.get()) && running.get()) {
                    try {
                        queue.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running.get()) return;
                task = queue.peek();
            }

            if (task != null) {
                processTask(task);
                synchronized (queue) {
                    queue.poll();
                    saveQueue();
                }
            }
        }
    }

    private void processTask(QueueTask task) {
        TaskExecutor executor = executors.get(task.getType());
        if (executor != null) {
            try {
                task.setStatus(QueueTask.Status.RUNNING);
                saveQueue();
                logger.info("Executing task {} via {}", task.getId(), executor.getClass().getSimpleName());

                executor.execute(task);

                if (task.getStatus() != QueueTask.Status.FAILED) {
                    task.setStatus(QueueTask.Status.DONE);
                }
            } catch (Exception e) {
                logger.error("Error executing task {}", task.getId(), e);
                task.setStatus(QueueTask.Status.FAILED);
            }
        } else {
            logger.error("No executor found for task type: {}", task.getType());
            task.setStatus(QueueTask.Status.FAILED);
        }
        saveQueue();
    }

    private void saveQueue() {
        synchronized (queue) {
            try (Writer w = new FileWriter(queueFile, StandardCharsets.UTF_8)) {
                gson.toJson(queue, w);
            } catch (IOException e) {
                logger.error("Failed to save queue", e);
            }
        }
    }

    private void loadQueue() {
        if (!queueFile.exists()) return;
        try (Reader r = new FileReader(queueFile, StandardCharsets.UTF_8)) {
            List<QueueTask> loaded = gson.fromJson(r, new TypeToken<List<QueueTask>>(){}.getType());
            if (loaded != null) {
                queue.addAll(loaded);
            }
        } catch (Exception e) {
            logger.error("Failed to load queue", e);
        }
    }
}