package com.plugins.dashboard;

import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.plugins.dashboard.internal.DashboardServer; // Liegt jetzt intern im Plugin

public class DashboardPlugin implements MediaPlugin {
    private DashboardServer server;

    @Override
    public String getName() { return "WebDashboard"; }
    @Override
    public String getVersion() { return "1.0.0"; }

    @Override
    public void onEnable(Kernel kernel) {
        // Server starten
        this.server = new DashboardServer(kernel);
        this.server.start();
        System.out.println("🔌 WebDashboard Plugin gestartet (Port 6875).");
    }

    @Override
    public void onDisable() {
        if (this.server != null) this.server.stop();
    }
}