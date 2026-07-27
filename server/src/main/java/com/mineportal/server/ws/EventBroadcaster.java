package com.mineportal.server.ws;

import com.mineportal.server.account.AccountSessionManager;
import com.mineportal.server.account.AccountView;
import com.mineportal.server.servers.ChatMessage;
import com.mineportal.server.servers.ServerConfig;
import com.mineportal.server.servers.ServerRegistry;
import com.mineportal.server.servers.ServerSummary;
import com.mineportal.server.servers.StatusEntry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks connected WebSocket sessions and pushes the same three message shapes the frontend
 * already expects: {type:"account"}, {type:"servers"}, {type:"server-event"}. Ported from the
 * broadcast logic in src/index.ts's `wss.on("connection", ...)`.
 */
@Component
public class EventBroadcaster {

    private final ServerRegistry registry;
    private final AccountSessionManager accountSessions;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public EventBroadcaster(ServerRegistry registry, AccountSessionManager accountSessions) {
        this.registry = registry;
        this.accountSessions = accountSessions;
    }

    public void register(WebSocketSession session) {
        sessions.add(session);
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session);
    }

    private String sidOf(WebSocketSession session) {
        Object sid = session.getAttributes().get("sid");
        return sid != null ? sid.toString() : null;
    }

    /** The DB owner id (logged-in Minecraft profile id) for whichever session owns this socket,
     * or null if that session isn't logged in — same resolution ServersController uses. */
    private String ownerIdOf(WebSocketSession session) {
        String sid = sidOf(session);
        if (sid == null) return null;
        var profile = accountSessions.get(sid).profile;
        return profile != null ? profile.id() : null;
    }

    public void sendAccountSnapshot(WebSocketSession session) {
        String sid = sidOf(session);
        Map<String, Object> account = sid == null
                ? Map.of("status", "logged-out")
                : AccountView.of(accountSessions.get(sid));
        send(session, Map.of("type", "account", "account", account));
    }

    /** Re-pushes this session's own account state — used right after a login attempt resolves. */
    public void refreshAccountFor(String sid) {
        for (WebSocketSession session : sessions) {
            if (sid.equals(sidOf(session))) sendAccountSnapshot(session);
        }
    }

    public void sendServersSnapshot(WebSocketSession session) {
        String ownerId = ownerIdOf(session);
        List<ServerSummary> servers = ownerId == null
                ? List.of()
                : registry.listServers(ownerId).stream().map(ServerSummary::of).toList();
        send(session, Map.of("type", "servers", "servers", servers));
    }

    /** Only pushed to sessions whose owning session actually owns this server — mirrors the
     * per-connection isServerOwnedByUser filter in the old Node backend's WS handler. */
    public void broadcastServerEvent(String serverId, Map<String, Object> payload) {
        for (WebSocketSession session : sessions) {
            String ownerId = ownerIdOf(session);
            if (ownerId == null || !registry.isOwnedByUser(serverId, ownerId)) continue;
            send(session, Map.of("type", "server-event", "serverId", serverId, "payload", payload));
        }
    }

    /** Re-pushes this session's own server list — used after a mutation (add/connect) so this
     * one browser tab refreshes without waiting for the next full-page load. */
    public void refreshServersFor(String sid) {
        for (WebSocketSession session : sessions) {
            if (sid.equals(sidOf(session))) sendServersSnapshot(session);
        }
    }

    /** Updates a server's status, records it in its history, and broadcasts the change — the
     * single choke point every status transition flows through, whether it's the temporary
     * connect stub (this step) or the real MCProtocolLib listener (later). Ported from
     * src/servers.ts's setStatus(). */
    public void setStatus(ServerConfig server, String status, boolean logged) {
        server.status = status;
        long timestamp = System.currentTimeMillis();
        server.pushStatusHistory(new StatusEntry(status, timestamp));
        broadcastServerEvent(server.id, Map.of(
                "type", "status",
                "status", status,
                "phase", server.phase,
                "connected", server.connected,
                "timestamp", timestamp,
                "logged", logged
        ));
    }

    public void chatReceived(ServerConfig server, ChatMessage message) {
        server.pushMessage(message);
        broadcastServerEvent(server.id, Map.of("type", "chat", "message", message));
    }

    private void send(WebSocketSession session, Object message) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (Exception e) {
            System.err.println("Failed to send WS message: " + e.getMessage());
        }
    }

}
