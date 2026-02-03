package com.plugins.dashboard.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Security validator for file system paths to prevent path traversal attacks.
 * Ensures all file operations stay within allowed directories.
 */
public class SecurePathValidator {

    // Allowed base directories (relative to working directory)
    private static final List<String> ALLOWED_ROOTS = Arrays.asList(
            "media_cache",
            "downloads",
            "temp");

    /**
     * Validates a user-provided path and returns the canonical file.
     * 
     * @param userPath The path provided by the user (can be relative)
     * @return Validated canonical File object
     * @throws SecurityException if path is outside allowed directories
     * @throws IOException       if canonical path cannot be resolved
     */
    public static File validatePath(String userPath) throws SecurityException, IOException {
        if (userPath == null || userPath.trim().isEmpty()) {
            throw new SecurityException("Path cannot be null or empty");
        }

        // Normalize the path - remove any '..' or '.' components
        Path normalized = Path.of(userPath).normalize();

        // Check for suspicious patterns
        if (normalized.toString().contains("..")) {
            throw new SecurityException("Path traversal detected: " + userPath);
        }

        // Try to find which allowed root this path belongs to
        File validatedFile = null;

        for (String root : ALLOWED_ROOTS) {
            File rootDir = new File(root).getCanonicalFile();

            String normalizedStr = normalized.toString().replace("\\", "/");
            String rootPrefix = root + "/";

            // If normalized path starts with root prefix, strip it
            String pathWithinRoot = normalizedStr;
            if (normalizedStr.startsWith(rootPrefix)) {
                pathWithinRoot = normalizedStr.substring(rootPrefix.length());
            } else if (normalizedStr.equals(root)) {
                // User path is exactly the root
                validatedFile = rootDir;
                break;
            } else if (normalizedStr.isEmpty() || normalizedStr.equals("")) {
                // Empty path defaults to first root
                validatedFile = rootDir;
                break;
            }

            // Try combining root with the path (without root prefix)
            File candidate = new File(rootDir, pathWithinRoot).getCanonicalFile();

            // Check if the canonical path is within the allowed root
            if (candidate.getPath().startsWith(rootDir.getPath())) {
                validatedFile = candidate;
                break;
            }
        }

        if (validatedFile == null) {
            throw new SecurityException(
                    "Path '" + userPath + "' is outside allowed directories: " + ALLOWED_ROOTS);
        }

        return validatedFile;
    }

    /**
     * Validates and ensures a path exists as a directory.
     * 
     * @param userPath The path provided by the user
     * @return Validated directory File object
     * @throws SecurityException if path is invalid or not a directory
     * @throws IOException       if canonical path cannot be resolved
     */
    public static File validateDirectory(String userPath) throws SecurityException, IOException {
        File file = validatePath(userPath);

        if (file.exists() && !file.isDirectory()) {
            throw new SecurityException("Path is not a directory: " + userPath);
        }

        return file;
    }

    /**
     * Validates and ensures a path exists as a file.
     * 
     * @param userPath The path provided by the user
     * @return Validated file File object
     * @throws SecurityException if path is invalid or not a file
     * @throws IOException       if canonical path cannot be resolved
     */
    public static File validateFile(String userPath) throws SecurityException, IOException {
        File file = validatePath(userPath);

        if (file.exists() && !file.isFile()) {
            throw new SecurityException("Path is not a file: " + userPath);
        }

        return file;
    }

    /**
     * Checks if a path is within allowed directories without exception.
     * 
     * @param userPath The path to check
     * @return true if path is valid, false otherwise
     */
    public static boolean isPathAllowed(String userPath) {
        try {
            validatePath(userPath);
            return true;
        } catch (SecurityException | IOException e) {
            return false;
        }
    }
}
