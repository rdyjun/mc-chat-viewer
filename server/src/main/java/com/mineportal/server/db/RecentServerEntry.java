package com.mineportal.server.db;

public record RecentServerEntry(String id, String host, int port, long lastConnectedAt) {
}
