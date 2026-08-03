package com.mineportal.server.desktop;

import com.mineportal.server.account.AccountSessionManager;
import com.mineportal.server.account.AccountState;
import com.mineportal.server.connection.ChatMessage;
import com.mineportal.server.servers.ServerConfig;
import com.mineportal.server.servers.ServerRegistry;
import com.mineportal.server.ws.EventBroadcaster;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 데스크톱 앱이 여는 아웃바운드 WS(/app-ws)의 서버 측. 사용자 브라우저용 /ws
 * (ServerWsHandler)와 대칭인 채널로, 여기서는 로그인 쿠키 대신 최초 메시지의
 * 페어링 코드/디바이스 토큰으로 이 커넥션이 어느 계정(ownerId) 것인지 인증한다.
 */
@Component
public class DesktopWsHandler extends TextWebSocketHandler {

    private final DesktopConnectionManager desktopConnections;
    private final AccountSessionManager accountSessions;
    private final ServerRegistry registry;
    private final EventBroadcaster broadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DesktopWsHandler(DesktopConnectionManager desktopConnections, AccountSessionManager accountSessions,
                             ServerRegistry registry, EventBroadcaster broadcaster) {
        this.desktopConnections = desktopConnections;
        this.accountSessions = accountSessions;
        this.registry = registry;
        this.broadcaster = broadcaster;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());
        String type = json.path("type").asText("");

        switch (type) {
            case "pair" -> handlePair(session, json.path("code").asText(null));
            case "resume" -> handleResume(session, json.path("deviceToken").asText(null));
            case "connected" -> handleConnected(session, json.path("serverId").asText(null));
            case "disconnected" -> handleDisconnected(session, json.path("serverId").asText(null), json.path("reason").asText(""));
            case "chat" -> handleChat(session, json.path("serverId").asText(null),
                    json.path("username").asText(""), json.path("message").asText(""));
            default -> { /* 알 수 없는 메시지 타입은 무시 */ }
        }
    }

    private void handlePair(WebSocketSession session, String code) throws Exception {
        String ownerId = code != null ? desktopConnections.consumePairingCode(code) : null;
        if (ownerId == null) {
            send(session, Map.of("type", "pair-failed"));
            return;
        }
        desktopConnections.register(ownerId, session);
        String deviceToken = desktopConnections.issueDeviceToken(ownerId);
        send(session, Map.of("type", "paired", "deviceToken", deviceToken));
        pushTokenIfLoggedIn(ownerId);
        broadcaster.refreshAccountForOwner(ownerId);
    }

    private void handleResume(WebSocketSession session, String deviceToken) throws Exception {
        String ownerId = desktopConnections.ownerForDeviceToken(deviceToken);
        if (ownerId == null) {
            send(session, Map.of("type", "pair-required"));
            return;
        }
        desktopConnections.register(ownerId, session);
        send(session, Map.of("type", "resumed"));
        pushTokenIfLoggedIn(ownerId);
        broadcaster.refreshAccountForOwner(ownerId);
    }

    /** 이 계정이 브라우저에서 이미 로그인돼있으면(앱이 붙기 전에 로그인이 끝났던 경우) 즉시 토큰을 밀어준다. */
    private void pushTokenIfLoggedIn(String ownerId) {
        AccountState state = accountSessions.findByOwnerId(ownerId);
        if (state != null) desktopConnections.pushTokenIfConnected(state);
    }

    private void handleConnected(WebSocketSession session, String serverId) {
        ServerConfig server = serverOf(session, serverId);
        if (server == null) return;
        server.connected = true;
        broadcaster.setStatus(server, "connected", true);
    }

    private void handleDisconnected(WebSocketSession session, String serverId, String reason) {
        ServerConfig server = serverOf(session, serverId);
        desktopConnections.unmarkRouted(serverId);
        if (server == null) return;
        server.phase = "closed";
        server.connected = false;
        broadcaster.setStatus(server, "disconnected: " + reason, true);
    }

    private void handleChat(WebSocketSession session, String serverId, String username, String message) {
        ServerConfig server = serverOf(session, serverId);
        if (server == null) return;
        broadcaster.chatReceived(server, new ChatMessage(username, message, System.currentTimeMillis()));
    }

    private ServerConfig serverOf(WebSocketSession session, String serverId) {
        String ownerId = desktopConnections.ownerIdOf(session);
        if (ownerId == null || serverId == null) return null;
        return registry.getServer(serverId, ownerId).orElse(null);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String ownerId = desktopConnections.ownerIdOf(session);
        desktopConnections.unregister(session);
        if (ownerId != null) broadcaster.refreshAccountForOwner(ownerId);
    }

    private void send(WebSocketSession session, Object payload) throws Exception {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    }

}
