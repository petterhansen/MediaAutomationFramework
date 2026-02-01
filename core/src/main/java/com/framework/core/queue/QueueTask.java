package com.framework.core.queue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class QueueTask {
    public enum Status { WAITING, RUNNING, DONE, FAILED }

    private final String id;
    private final String type;
    private final long timestamp;
    private Status status;
    private final Map<String, Object> payload;

    private int totalItems = 0;
    private int processedItems = 0;

    public QueueTask(String type) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.status = Status.WAITING;
        this.payload = new HashMap<>();
    }

    public void addParameter(String key, Object value) {
        payload.put(key, value);
    }

    public Object getParameter(String key) {
        return payload.get(key);
    }

    // Für Dashboard
    public String getName() {
        if (payload.containsKey("folder")) return payload.get("folder").toString();
        if (payload.containsKey("name")) return payload.get("name").toString();
        if (payload.containsKey("query")) return payload.get("query").toString();
        return type;
    }

    public String getString(String key) {
        Object val = payload.get(key);
        return val != null ? val.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object val = payload.get(key);
        if (val instanceof List) {
            return (List<String>) val;
        }
        return Collections.emptyList();
    }

    public int getInt(String key, int def) {
        Object val = payload.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Exception e) {}
        }
        return def;
    }

    public long getLong(String key, long def) {
        Object val = payload.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try { return Long.parseLong((String) val); } catch (Exception e) {}
        }
        return def;
    }

    // Getters & Setters
    public String getId() { return id; }
    public String getType() { return type; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public void setTotalItems(int t) { this.totalItems = t; }
    public int getTotalItems() { return totalItems; }
    public void incrementProcessed() { this.processedItems++; }
    public int getProcessedItems() { return processedItems; }
}