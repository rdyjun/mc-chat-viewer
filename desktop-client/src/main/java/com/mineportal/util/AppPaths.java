package com.mineportal.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves the per-user config directory (~/.mineportal) and its files. */
public final class AppPaths {

    public static Path dir() {
        Path dir = Paths.get(System.getProperty("user.home"), ".mineportal");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    public static Path serversFile() {
        return dir().resolve("servers.json");
    }

    public static Path sessionFile() {
        return dir().resolve("session.json");
    }

    private AppPaths() {
    }
}
