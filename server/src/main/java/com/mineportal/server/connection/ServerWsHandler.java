package com.mineportal.server.connection;

import com.mineportal.server.account.AccountSessionManager;
import com.mineportal.server.servers.ServerRegistry;
import com.mineportal.server.ws.EventBroadcaster;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ServerWsHandler extends TextWebSocketHandler {

    private final EventBroadcaster broadcaster;
    private final AccountSessionManager accountSessions;
    private final ServerRegistry registry;
    private final McConnectionManager mcConnections;

    public ServerWsHandler(EventBroadcaster broadcaster, AccountSessionManager accountSessions,
                            ServerRegistry registry, McConnectionManager mcConnections) {
        this.broadcaster = broadcaster;
        this.accountSessions = accountSessions;
        this.registry = registry;
        this.mcConnections = mcConnections;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.register(session);
        broadcaster.sendAccountSnapshot(session);
        broadcaster.sendServersSnapshot(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sid = (String) session.getAttributes().get("sid");
        broadcaster.unregister(session);
        if (sid == null || broadcaster.hasSessionFor(sid)) return; // 아직 같은 계정의 다른 탭이 열려있으면 유지

        var profile = accountSessions.get(sid).profile;
        if (profile == null) return;
        String ownerId = profile.id();
        for (var server : registry.listServers(ownerId)) {
            if ("active".equals(server.phase)) {
                mcConnections.disconnect(server.id);
            }
        }
    }

}
