package com.mineportal.server.ws;

import com.mineportal.server.account.AccountSessionManager;
import com.mineportal.server.account.AccountView;
import com.mineportal.server.connection.ChatMessage;
import com.mineportal.server.desktop.DesktopConnectionManager;
import com.mineportal.server.servers.ServerConfig;
import com.mineportal.server.servers.ServerRegistry;
import com.mineportal.server.servers.ServerSummary;
import com.mineportal.server.status.StatusEntry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 연결된 WebSocket 세션들을 추적하며, 프론트엔드가 이미 기대하고 있는 동일한 세 가지
 * 메시지 형태({type:"account"}, {type:"servers"}, {type:"server-event"})를 전송한다.
 * src/index.ts의 `wss.on("connection", ...)`에 있던 브로드캐스트 로직을 포팅한 것.
 */
@Component
public class EventBroadcaster {

    private final ServerRegistry registry;
    private final AccountSessionManager accountSessions;
    private final DesktopConnectionManager desktopConnections;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public EventBroadcaster(ServerRegistry registry, AccountSessionManager accountSessions,
                             DesktopConnectionManager desktopConnections) {
        this.registry = registry;
        this.accountSessions = accountSessions;
        this.desktopConnections = desktopConnections;
    }

    public void register(WebSocketSession session) {
        sessions.add(session);
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session);
    }

    /** 주어진 sid를 소유한 WS 세션이 (다른 탭 등으로) 아직 남아있는지 확인한다 — 마지막
     * 탭이 닫혔을 때만 좀비 마인크래프트 연결을 정리하기 위해 사용된다. */
    public boolean hasSessionFor(String sid) {
        for (WebSocketSession session : sessions) {
            if (sid.equals(sidOf(session))) return true;
        }
        return false;
    }

    private String sidOf(WebSocketSession session) {
        Object sid = session.getAttributes().get("sid");
        return sid != null ? sid.toString() : null;
    }

    /** 이 소켓을 소유한 세션의 DB owner id(로그인된 마인크래프트 프로필 id)를 반환하며,
     * 그 세션이 로그인하지 않았다면 null이다 — ServersController와 동일한 방식으로 계산한다. */
    private String ownerIdOf(WebSocketSession session) {
        String sid = sidOf(session);
        if (sid == null) return null;
        var profile = accountSessions.get(sid).profile;
        return profile != null ? profile.id() : null;
    }

    public void sendAccountSnapshot(WebSocketSession session) {
        String sid = sidOf(session);
        Map<String, Object> account = new HashMap<>(sid == null
                ? Map.of("status", "logged-out")
                : AccountView.of(accountSessions.get(sid)));
        String ownerId = ownerIdOf(session);
        account.put("desktopConnected", ownerId != null && desktopConnections.isConnected(ownerId));
        send(session, Map.of("type", "account", "account", account));
    }

    /** 이 세션 자신의 계정 상태를 다시 전송한다 — 로그인 시도가 끝난 직후에 사용된다. */
    public void refreshAccountFor(String sid) {
        for (WebSocketSession session : sessions) {
            if (sid.equals(sidOf(session))) sendAccountSnapshot(session);
        }
    }

    /** 데스크톱 앱이 방금 페어링/재접속되거나 끊겼을 때, 이 계정을 보고 있는 모든 브라우저
     * 탭에 최신 desktopConnected 값을 다시 밀어준다. */
    public void refreshAccountForOwner(String ownerId) {
        for (WebSocketSession session : sessions) {
            if (ownerId.equals(ownerIdOf(session))) sendAccountSnapshot(session);
        }
    }

    public void sendServersSnapshot(WebSocketSession session) {
        String ownerId = ownerIdOf(session);
        List<ServerSummary> servers = ownerId == null
                ? List.of()
                : registry.listServers(ownerId).stream().map(ServerSummary::of).toList();
        send(session, Map.of("type", "servers", "servers", servers));
    }

    /** 실제로 이 서버를 소유한 세션에게만 전송된다 — 예전 Node 백엔드의 WS 핸들러에 있던
     * 연결별 isServerOwnedByUser 필터를 그대로 반영한 것. */
    public void broadcastServerEvent(String serverId, Map<String, Object> payload) {
        for (WebSocketSession session : sessions) {
            String ownerId = ownerIdOf(session);
            if (ownerId == null || !registry.isOwnedByUser(serverId, ownerId)) continue;
            send(session, Map.of("type", "server-event", "serverId", serverId, "payload", payload));
        }
    }

    /** 이 세션 자신의 서버 목록을 다시 전송한다 — 변경 작업(추가/연결) 이후에 사용되어,
     * 이 브라우저 탭이 다음 전체 페이지 로드를 기다리지 않고도 갱신되게 한다. */
    public void refreshServersFor(String sid) {
        for (WebSocketSession session : sessions) {
            if (sid.equals(sidOf(session))) sendServersSnapshot(session);
        }
    }

    /** 서버의 상태를 갱신하고, 이력에 기록하고, 변경 사항을 브로드캐스트한다 — 임시
     * connect 스텁(현재 단계)이든 실제 MCProtocolLib 리스너(이후 단계)든, 모든 상태
     * 전환이 반드시 거쳐가는 유일한 관문이다. src/servers.ts의 setStatus()를 포팅한 것. */
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
