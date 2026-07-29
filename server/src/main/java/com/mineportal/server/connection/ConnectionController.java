package com.mineportal.server.connection;

import com.mineportal.server.account.AccountSessionManager;
import com.mineportal.server.account.AccountState;
import com.mineportal.server.servers.ServerConfig;
import com.mineportal.server.servers.ServerRegistry;
import com.mineportal.server.status.PingService;
import com.mineportal.server.ws.EventBroadcaster;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/servers")
public class ConnectionController {

    private final ServerRegistry registry;
    private final EventBroadcaster broadcaster;
    private final AccountSessionManager accountSessions;
    private final McConnectionManager mcConnections;
    private final PingService pingService;

    public ConnectionController(ServerRegistry registry, EventBroadcaster broadcaster,
                                 AccountSessionManager accountSessions, McConnectionManager mcConnections,
                                 PingService pingService) {
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.accountSessions = accountSessions;
        this.mcConnections = mcConnections;
        this.pingService = pingService;
    }

    /** DB의 owner id는 언제나 로그인된 마인크래프트 프로필 id다 — 서버 측에서 이 세션
     * 고유의 AccountState로부터 계산되며, 클라이언트가 보내는 값은 절대 사용하지 않는다.
     * null이면 "이 브라우저 세션은 로그인하지 않음"을 뜻한다. */
    private String ownerId(String sid) {
        var profile = accountSessions.get(sid).profile;
        return profile != null ? profile.id() : null;
    }

    @PostMapping("/{id}/connect")
    public ResponseEntity<?> connect(@RequestAttribute("sid") String sid, @PathVariable String id) {
        String ownerId = ownerId(sid);
        if (ownerId == null) {
            return ResponseEntity.status(409).body(Map.of("error", "Log in with a Microsoft account before connecting to a server", "code", "not-logged-in"));
        }
        ServerConfig server = registry.getServer(id, ownerId).orElse(null);
        if (server == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "server not found"));
        }
        if (pingService.isPrivateHost(server.host)) {
            return ResponseEntity.badRequest().body(Map.of("error", "cannot connect to private/internal hosts"));
        }
        if ("active".equals(server.phase)) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        AccountState account = accountSessions.get(sid);
        server.phase = "active";
        broadcaster.setStatus(server, "connecting", true);
        mcConnections.connect(server, account);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/chat")
    public ResponseEntity<?> chat(@RequestAttribute("sid") String sid, @PathVariable String id, @RequestBody Map<String, String> body) {
        String ownerId = ownerId(sid);
        if (ownerId == null || registry.getServer(id, ownerId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "server not found"));
        }
        String message = body.get("message");
        if (message == null || message.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }
        try {
            mcConnections.sendChat(id, message);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (NotConnectedException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage(), "code", "not-connected"));
        }
    }

}
