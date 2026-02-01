package com.framework.common.auth;

import com.framework.core.Kernel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Verwaltet eine Datenbank aller bekannten Telegram-User.
 * Location: com.framework.common.auth
 */
public class UserManager {
    private static final Logger logger = LoggerFactory.getLogger(UserManager.class);
    private final File dbFile;
    private final Gson gson;
    private final Map<Long, UserData> users = new ConcurrentHashMap<>();
    private final Map<String, Long> usernameIndex = new ConcurrentHashMap<>();

    public UserManager(Kernel kernel) {
        this.dbFile = new File(kernel.getToolsDir(), "user_db.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    public static class UserData {
        public long id;
        public String username;
        public String firstName;
        public String lastName;
        public String languageCode;
        public boolean isPremium;
        public long firstSeen;
        public long lastSeen;
    }

    public void trackUser(JsonObject tgUser) {
        if (tgUser == null) return;

        long id = tgUser.get("id").getAsLong();
        UserData data = users.getOrDefault(id, new UserData());

        data.id = id;
        data.isPremium = tgUser.has("is_premium") && tgUser.get("is_premium").getAsBoolean();

        if (tgUser.has("username")) {
            String newName = tgUser.get("username").getAsString();
            if (data.username != null && !data.username.equalsIgnoreCase(newName)) {
                usernameIndex.remove(data.username.toLowerCase());
            }
            data.username = newName;
            usernameIndex.put(newName.toLowerCase(), id);
        }

        if (tgUser.has("first_name")) data.firstName = tgUser.get("first_name").getAsString();
        if (tgUser.has("last_name")) data.lastName = tgUser.get("last_name").getAsString();
        if (tgUser.has("language_code")) data.languageCode = tgUser.get("language_code").getAsString();

        long now = System.currentTimeMillis();
        if (data.firstSeen == 0) data.firstSeen = now;
        data.lastSeen = now;

        users.put(id, data);
        save();
    }

    public long resolveId(String input) {
        if (input.startsWith("@")) {
            String clean = input.substring(1).toLowerCase();
            return usernameIndex.getOrDefault(clean, -1L);
        }
        try {
            return Long.parseLong(input);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    public UserData getUser(long id) {
        return users.get(id);
    }

    public List<UserData> searchUsers(String query) {
        if (query == null || query.trim().isEmpty()) {
            return users.values().stream()
                    .sorted((u1, u2) -> Long.compare(u2.lastSeen, u1.lastSeen))
                    .limit(50)
                    .collect(Collectors.toList());
        }
        String q = query.toLowerCase().trim();
        return users.values().stream()
                .filter(u -> String.valueOf(u.id).contains(q) ||
                        (u.username != null && u.username.toLowerCase().contains(q)) ||
                        (u.firstName != null && u.firstName.toLowerCase().contains(q)))
                .sorted((u1, u2) -> Long.compare(u2.lastSeen, u1.lastSeen))
                .limit(50)
                .collect(Collectors.toList());
    }

    private void save() {
        try (Writer w = new FileWriter(dbFile, StandardCharsets.UTF_8)) {
            gson.toJson(users, w);
        } catch (IOException e) { logger.error("UserDB Save Error", e); }
    }

    private void load() {
        if (!dbFile.exists()) return;
        try (Reader r = new FileReader(dbFile, StandardCharsets.UTF_8)) {
            Map<Long, UserData> loaded = gson.fromJson(r, new TypeToken<ConcurrentHashMap<Long, UserData>>(){}.getType());
            if (loaded != null) {
                users.putAll(loaded);
                users.values().forEach(u -> {
                    if (u.username != null) usernameIndex.put(u.username.toLowerCase(), u.id);
                });
            }
        } catch (Exception e) { logger.error("UserDB Load Error", e); }
    }
}