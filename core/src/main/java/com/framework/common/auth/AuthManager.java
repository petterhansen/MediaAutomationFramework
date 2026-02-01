package com.framework.common.auth;

import com.framework.core.Kernel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Volle Implementierung von src/utils/AuthManager.java
 *
 */
public class AuthManager {
    private static final Logger logger = LoggerFactory.getLogger(AuthManager.class);
    private final File authFile;
    private final Gson gson;
    private AuthData data;

    public AuthManager(Kernel kernel) {
        this.authFile = new File(kernel.getToolsDir(), "auth.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    private static class AuthData {
        String adminUsername = "admin";
        String adminPassword = "";
        Set<Long> allowedTelegramIds = new HashSet<>();
        Set<Long> guestIds = new HashSet<>();
    }

    private void load() {
        if (authFile.exists()) {
            try (Reader r = new FileReader(authFile, StandardCharsets.UTF_8)) {
                data = gson.fromJson(r, AuthData.class);
            } catch (Exception e) {
                logger.error("Auth Load Error", e);
                data = new AuthData();
            }
        } else {
            data = new AuthData();
            data.adminPassword = UUID.randomUUID().toString().substring(0, 8);
            save();
            logger.warn("🔐 NEW AUTH FILE CREATED! Admin Pass: {}", data.adminPassword);
        }
        if (data.guestIds == null) data.guestIds = new HashSet<>();
    }

    public synchronized void save() {
        try (Writer w = new FileWriter(authFile, StandardCharsets.UTF_8)) {
            gson.toJson(data, w);
        } catch (IOException e) { logger.error("Failed to save auth", e); }
    }

    public boolean checkWebCredentials(String user, String pass) {
        return data.adminUsername.equals(user) && data.adminPassword.equals(pass);
    }

    public boolean isAdmin(long userId) { return data.allowedTelegramIds.contains(userId); }
    public boolean isGuest(long userId) { return data.guestIds.contains(userId); }
    public boolean isAuthorized(long userId) { return isAdmin(userId) || isGuest(userId); }

    public boolean tryTelegramLogin(long userId, String password) {
        if (data.adminPassword.equals(password)) {
            data.allowedTelegramIds.add(userId);
            data.guestIds.remove(userId);
            save();
            return true;
        }
        return false;
    }

    // --- NEUE METHODEN FÜR PLUGIN-SUPPORT ---

    public void addGuest(long userId) {
        if (data.guestIds == null) data.guestIds = new HashSet<>();
        data.guestIds.add(userId);
        save(); // Speichert sofort in auth.json
        logger.info("User {} added to Guest List.", userId);
    }

    public void removeGuest(long userId) {
        if (data.guestIds != null && data.guestIds.contains(userId)) {
            data.guestIds.remove(userId);
            save();
            logger.info("User {} removed from Guest List.", userId);
        }
    }
}