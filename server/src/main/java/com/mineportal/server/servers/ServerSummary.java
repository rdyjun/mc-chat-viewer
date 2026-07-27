package com.mineportal.server.servers;

public record ServerSummary(String id, String host, int port, String version, String status, String phase, boolean connected) {

    public static ServerSummary of(ServerConfig s) {
        return new ServerSummary(s.id, s.host, s.port, s.version, s.status, s.phase, s.connected);
    }

}
