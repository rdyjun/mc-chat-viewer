package com.mineportal.server.servers;

import java.util.List;

public record ServerDetail(String id, String host, int port, String version, String status, String phase,
                            boolean connected, List<ChatMessage> messages, List<StatusEntry> statusHistory) {

    public static ServerDetail of(ServerConfig s) {
        return new ServerDetail(s.id, s.host, s.port, s.version, s.status, s.phase, s.connected,
                s.getMessages(), s.getStatusHistory());
    }

}
