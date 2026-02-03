package com.framework.api;

/**
 * Interface für Chat-Befehle (Plugins können eigene Befehle registrieren).
 */
public interface CommandHandler {
    void handle(long chatId, Integer threadId, String command, String[] args);
}