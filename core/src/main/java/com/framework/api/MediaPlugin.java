package com.framework.api;

import com.framework.core.Kernel;

public interface MediaPlugin {
    // Name des Plugins (z.B. "RedditScraper")
    String getName();

    // Version (z.B. "1.0.0")
    String getVersion();

    // Wird beim Start aufgerufen. Hier registriert das Plugin seine Listener/Sources.
    void onEnable(Kernel kernel);

    // Wird beim Beenden aufgerufen (Cleanup).
    void onDisable();
}