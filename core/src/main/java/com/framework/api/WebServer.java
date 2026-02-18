package com.framework.api;

import com.sun.net.httpserver.HttpHandler;
import java.nio.file.Path;

/**
 * Interface for the WebServer (Dashboard) to allow plugins to register routes
 * without depending on the concrete DashboardServer implementation.
 */
public interface WebServer {
    /**
     * Register a public or protected route.
     * 
     * @param path        The context path (e.g. "/api/myplugin")
     * @param handler     The HttpHandler to handle requests
     * @param isProtected If true, requires authentication (and potentially admin
     *                    role)
     */
    void registerRoute(String path, HttpHandler handler, boolean isProtected);

    /**
     * Register a static file handler for a directory.
     * 
     * @param path           The context path (e.g. "/my-web-ui")
     * @param fileSystemPath The directory on disk
     * @param isProtected    If true, requires authentication
     */
    void registerStaticRoute(String path, Path fileSystemPath, boolean isProtected);

    /**
     * Unregister a route (useful for plugin unloading).
     * 
     * @param path The context path to remove
     */
    void unregisterRoute(String path);
}
