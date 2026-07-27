package com.mineportal.server.db;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Schema and queries ported 1:1 from the old Node backend's src/db.ts. */
@Repository
public class ServerRepository {

    private final JdbcTemplate jdbc;

    public ServerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS users (
                  id TEXT PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS servers (
                  id TEXT PRIMARY KEY,
                  host TEXT NOT NULL,
                  port INTEGER NOT NULL,
                  version TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_servers (
                  user_id TEXT NOT NULL REFERENCES users(id),
                  server_id TEXT NOT NULL REFERENCES servers(id),
                  PRIMARY KEY (user_id, server_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS connection_logs (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  server_id TEXT NOT NULL,
                  user_id TEXT NOT NULL,
                  host TEXT NOT NULL,
                  port INTEGER NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """);
    }

    public void upsertUser(String userId) {
        jdbc.update("INSERT OR IGNORE INTO users (id) VALUES (?)", userId);
    }

    public void insertServerRow(String id, String host, int port, String version) {
        jdbc.update("INSERT INTO servers (id, host, port, version) VALUES (?, ?, ?, ?)", id, host, port, version);
    }

    public void linkUserServer(String userId, String serverId) {
        jdbc.update("INSERT OR IGNORE INTO user_servers (user_id, server_id) VALUES (?, ?)", userId, serverId);
    }

    public List<ServerRow> listServerRowsForUser(String userId) {
        return jdbc.query("""
                SELECT s.id, s.host, s.port, s.version
                FROM servers s
                JOIN user_servers us ON us.server_id = s.id
                WHERE us.user_id = ?
                """,
                (rs, rowNum) -> new ServerRow(rs.getString("id"), rs.getString("host"), rs.getInt("port"), rs.getString("version")),
                userId);
    }

    /** Case-insensitive host match within one user's own server list — used to reject duplicate adds. */
    public Optional<ServerRow> findUserServerByAddress(String userId, String host, int port) {
        try {
            ServerRow row = jdbc.queryForObject("""
                    SELECT s.id, s.host, s.port, s.version
                    FROM servers s
                    JOIN user_servers us ON us.server_id = s.id
                    WHERE us.user_id = ? AND LOWER(s.host) = LOWER(?) AND s.port = ?
                    """,
                    (rs, rowNum) -> new ServerRow(rs.getString("id"), rs.getString("host"), rs.getInt("port"), rs.getString("version")),
                    userId, host, port);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ServerRow> listAllServerRows() {
        return jdbc.query("SELECT id, host, port, version FROM servers",
                (rs, rowNum) -> new ServerRow(rs.getString("id"), rs.getString("host"), rs.getInt("port"), rs.getString("version")));
    }

    public boolean isServerOwnedByUser(String serverId, String userId) {
        List<Integer> rows = jdbc.query("SELECT 1 FROM user_servers WHERE server_id = ? AND user_id = ?",
                (rs, rowNum) -> 1, serverId, userId);
        return !rows.isEmpty();
    }

    /** Fire-and-forget: records a connect attempt for the "인기 서버" ranking. Never throws — a
     * logging hiccup should never be able to break someone's actual connect flow. Runs on its
     * own Thread so the caller doesn't wait on the DB write. */
    public void logConnection(String serverId, String userId, String host, int port) {
        new Thread(() -> {
            try {
                jdbc.update("INSERT INTO connection_logs (server_id, user_id, host, port, created_at) VALUES (?, ?, ?, ?, ?)",
                        serverId, userId, host, port, System.currentTimeMillis());
            } catch (Exception e) {
                System.err.println("Failed to record connection log: " + e.getMessage());
            }
        }, "log-connection").start();
    }

    /** This user's own most-recently-connected servers, most recent first. */
    public List<RecentServerEntry> recentServersForUser(String userId, int limit) {
        return jdbc.query("""
                SELECT server_id as id, host, port, MAX(created_at) as lastConnectedAt
                FROM connection_logs
                WHERE user_id = ?
                GROUP BY server_id
                ORDER BY lastConnectedAt DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new RecentServerEntry(rs.getString("id"), rs.getString("host"), rs.getInt("port"), rs.getLong("lastConnectedAt")),
                userId, limit);
    }

}
