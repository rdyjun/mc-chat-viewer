package com.mineportal.server.servers;

import com.mineportal.server.db.RecentServerEntry;
import com.mineportal.server.db.ServerRepository;
import com.mineportal.server.db.ServerRow;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** ServerRepository를 기반으로 하는 ServerConfig의 인메모리 레지스트리. src/servers.ts를 포팅한 것. */
@Component
public class ServerRegistry {

    private final ServerRepository repository;
    private final Map<String, ServerConfig> servers = new ConcurrentHashMap<>();

    public ServerRegistry(ServerRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void rehydrate() {
        for (ServerRow row : repository.listAllServerRows()) {
            servers.put(row.id(), new ServerConfig(row.id(), row.host(), row.port(), row.version()));
        }
    }

    /** userId가 소유하는 서버를 저장 목록에 추가한다. 실제로 연결하지는 않는다. */
    public ServerConfig addServer(String host, int port, String version, String userId) {
        String id = UUID.randomUUID().toString();
        repository.insertServerRow(id, host, port, version);
        if (!repository.linkUserServer(userId, id, host, port)) {
            repository.deleteServerRow(id);
            throw new DuplicateServerException();
        }
        ServerConfig server = new ServerConfig(id, host, port, version);
        servers.put(id, server);
        return server;
    }

    /** userId가 추가한 서버만 반환한다 — 소유권에 대한 신뢰할 수 있는 근거는 DB의 user_servers 매핑이다. */
    public List<ServerConfig> listServers(String userId) {
        return repository.listServerRowsForUser(userId).stream()
                .map(row -> servers.get(row.id()))
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    public Optional<ServerConfig> getServer(String id, String userId) {
        if (!repository.isServerOwnedByUser(id, userId)) return Optional.empty();
        return Optional.ofNullable(servers.get(id));
    }

    public Optional<ServerConfig> getServerUnchecked(String id) {
        return Optional.ofNullable(servers.get(id));
    }

    public boolean isOwnedByUser(String serverId, String userId) {
        return repository.isServerOwnedByUser(serverId, userId);
    }

    public void logConnection(String serverId, String userId, String host, int port) {
        repository.logConnection(serverId, userId, host, port);
    }

    public List<RecentServerEntry> recentServersForUser(String userId, int limit) {
        return repository.recentServersForUser(userId, limit);
    }

}
