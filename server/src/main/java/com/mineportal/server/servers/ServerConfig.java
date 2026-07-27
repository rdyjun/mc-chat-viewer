package com.mineportal.server.servers;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * In-memory runtime state for one saved server, keyed by id in ServerRegistry. Rehydrated from
 * the DB (host/port/version) at startup; status/phase/connected/messages start fresh since a
 * live connection can't survive a process restart. Ported from src/servers.ts's ServerConfig.
 */
public class ServerConfig {

    private static final int MAX_HISTORY = 200;

    public final String id;
    public final String host;
    public final int port;
    public final String version;

    /** Free-text display status (from the client, or "idle" before any attempt). */
    public volatile String status = "idle";
    /** Coarse state driving whether a new connect attempt is allowed — status is just display text. */
    public volatile String phase = "idle"; // "idle" | "active" | "closed"
    /** True once the connection has actually reached the play state. */
    public volatile boolean connected = false;

    private final List<StatusEntry> statusHistory = Collections.synchronizedList(new LinkedList<>());
    private final List<ChatMessage> messages = Collections.synchronizedList(new LinkedList<>());

    public ServerConfig(String id, String host, int port, String version) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.version = version;
    }

    public void pushStatusHistory(StatusEntry entry) {
        statusHistory.add(entry);
        if (statusHistory.size() > MAX_HISTORY) statusHistory.remove(0);
    }

    public void pushMessage(ChatMessage message) {
        messages.add(message);
        if (messages.size() > MAX_HISTORY) messages.remove(0);
    }

    public List<StatusEntry> getStatusHistory() {
        return List.copyOf(statusHistory);
    }

    public List<ChatMessage> getMessages() {
        return List.copyOf(messages);
    }

}
