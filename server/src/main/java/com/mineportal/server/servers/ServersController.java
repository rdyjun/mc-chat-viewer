package com.mineportal.server.servers;

import com.mineportal.server.account.AccountSessionManager;
import com.mineportal.server.db.RecentServerEntry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/servers")
public class ServersController {

    private final ServerRegistry registry;
    private final AccountSessionManager accountSessions;

    public ServersController(ServerRegistry registry, AccountSessionManager accountSessions) {
        this.registry = registry;
        this.accountSessions = accountSessions;
    }

    /** DB의 owner id는 언제나 로그인된 마인크래프트 프로필 id다 — 서버 측에서 이 세션
     * 고유의 AccountState로부터 계산되며, 클라이언트가 보내는 값은 절대 사용하지 않는다.
     * null이면 "이 브라우저 세션은 로그인하지 않음"을 뜻한다. */
    private String ownerId(String sid) {
        var profile = accountSessions.get(sid).profile;
        return profile != null ? profile.id() : null;
    }

    @GetMapping
    public List<ServerSummary> list(@RequestAttribute("sid") String sid) {
        String ownerId = ownerId(sid);
        if (ownerId == null) return List.of();
        return registry.listServers(ownerId).stream().map(ServerSummary::of).toList();
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestAttribute("sid") String sid, @RequestBody AddServerRequest body) {
        String ownerId = ownerId(sid);
        if (ownerId == null) {
            return ResponseEntity.status(409).body(Map.of("error", "Log in before adding a server", "code", "not-logged-in"));
        }
        if (body.host() == null || body.host().isBlank() || body.version() == null || body.version().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "host and version are required"));
        }
        ParsedAddress parsed = parseServerAddress(body.host().trim());
        try {
            ServerConfig server = registry.addServer(parsed.host(), parsed.port(), body.version(), ownerId);
            return ResponseEntity.status(HttpStatus.CREATED).body(ServerSummary.of(server));
        } catch (DuplicateServerException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/recent")
    public List<RecentServerEntry> recent(@RequestAttribute("sid") String sid) {
        String ownerId = ownerId(sid);
        return ownerId == null ? List.of() : registry.recentServersForUser(ownerId, 5);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@RequestAttribute("sid") String sid, @PathVariable String id) {
        String ownerId = ownerId(sid);
        if (ownerId == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "server not found"));
        return registry.getServer(id, ownerId)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(ServerDetail.of(s)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "server not found")));
    }

    // 사용자는 "host:port"(또는 host만) 형태로 하나의 필드에 입력한다 — 이를 앱의 나머지
    // 부분(DB 컬럼, 프로토콜 클라이언트, ping)이 여전히 별도로 저장/사용하는 두 값으로
    // 분리한다.
    private ParsedAddress parseServerAddress(String address) {
        int idx = address.lastIndexOf(':');
        if (idx == -1) return new ParsedAddress(address, 25565);
        String host = address.substring(0, idx);
        String portStr = address.substring(idx + 1);
        try {
            int port = Integer.parseInt(portStr);
            if (host.isEmpty() || port <= 0 || port > 65535) return new ParsedAddress(address, 25565);
            return new ParsedAddress(host, port);
        } catch (NumberFormatException e) {
            return new ParsedAddress(address, 25565);
        }
    }

    private record ParsedAddress(String host, int port) {
    }

}
