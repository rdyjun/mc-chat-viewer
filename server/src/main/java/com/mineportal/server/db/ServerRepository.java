package com.mineportal.server.db;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

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
                  host TEXT,
                  port INTEGER,
                  PRIMARY KEY (user_id, server_id)
                )
                """);
        // Upgrade path for installs created before host/port were added to this table.
        jdbc.execute("ALTER TABLE user_servers ADD COLUMN IF NOT EXISTS host TEXT");
        jdbc.execute("ALTER TABLE user_servers ADD COLUMN IF NOT EXISTS port INTEGER");
        jdbc.execute("""
                UPDATE user_servers us SET host = s.host, port = s.port
                FROM servers s WHERE us.server_id = s.id AND us.host IS NULL
                """);
        jdbc.execute("ALTER TABLE user_servers ALTER COLUMN host SET NOT NULL");
        jdbc.execute("ALTER TABLE user_servers ALTER COLUMN port SET NOT NULL");
        // Enforces "one user can't own the same address twice" at the DB level instead of a
        // check-then-insert race in application code.
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS user_servers_addr_uq
                ON user_servers (user_id, LOWER(host), port)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS connection_logs (
                  id BIGSERIAL PRIMARY KEY,
                  server_id TEXT NOT NULL,
                  user_id TEXT NOT NULL,
                  host TEXT NOT NULL,
                  port INTEGER NOT NULL,
                  created_at BIGINT NOT NULL
                )
                """);
    }

    public void upsertUser(String userId) {
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT (id) DO NOTHING", userId);
    }

    public void insertServerRow(String id, String host, int port, String version) {
        jdbc.update("INSERT INTO servers (id, host, port, version) VALUES (?, ?, ?, ?)", id, host, port, version);
    }

    /** Atomically links a server to a user, rejecting a duplicate (user_id, host, port) address
     * via the DB-level unique index instead of a separate check-then-insert query.
     * @return true if the link was created, false if this user already owns that address. */
    public boolean linkUserServer(String userId, String serverId, String host, int port) {
        int rows = jdbc.update("""
                INSERT INTO user_servers (user_id, server_id, host, port) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, (LOWER(host)), port) DO NOTHING
                """,
                userId, serverId, host, port);
        return rows > 0;
    }

    public void deleteServerRow(String serverId) {
        jdbc.update("DELETE FROM servers WHERE id = ?", serverId);
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
