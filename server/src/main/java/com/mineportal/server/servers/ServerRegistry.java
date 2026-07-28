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

/** In-memory registry of ServerConfig, backed by ServerRepository. Ported from src/servers.ts. */
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

    /** Adds a server to the saved list, owned by userId. Does not connect. */
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

    /** Only the servers userId has added — the DB's user_servers mapping is the source of truth for ownership. */
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
