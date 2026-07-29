package com.mineportal.server.db;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 예전 Node 백엔드의 src/db.ts에서 스키마와 쿼리를 1:1로 포팅한 것. */
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
        // 이 테이블에 host/port가 추가되기 이전에 설치된 환경을 위한 업그레이드 경로.
        jdbc.execute("ALTER TABLE user_servers ADD COLUMN IF NOT EXISTS host TEXT");
        jdbc.execute("ALTER TABLE user_servers ADD COLUMN IF NOT EXISTS port INTEGER");
        jdbc.execute("""
                UPDATE user_servers us SET host = s.host, port = s.port
                FROM servers s WHERE us.server_id = s.id AND us.host IS NULL
                """);
        jdbc.execute("ALTER TABLE user_servers ALTER COLUMN host SET NOT NULL");
        jdbc.execute("ALTER TABLE user_servers ALTER COLUMN port SET NOT NULL");
        // "한 사용자가 같은 주소를 두 번 소유할 수 없다"는 제약을, 애플리케이션 코드에서
        // check-then-insert 방식으로 처리할 때 생기는 경쟁 상태 대신 DB 레벨에서 강제한다.
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

    /** 서버를 사용자에게 원자적으로 연결하며, 별도의 check-then-insert 쿼리 대신 DB 레벨의
     * unique 인덱스를 통해 (user_id, host, port) 주소 중복을 거부한다.
     * @return 연결이 새로 생성됐으면 true, 이 사용자가 이미 그 주소를 소유하고 있었으면 false. */
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

    /** Fire-and-forget 방식: "인기 서버" 순위 산정을 위해 연결 시도를 기록한다. 절대 예외를
     * 던지지 않는다 — 로깅 과정의 사소한 문제가 실제 사용자의 연결 흐름을 깨뜨려서는 안
     * 된다. 호출자가 DB 쓰기를 기다리지 않도록 별도 Thread에서 실행한다. */
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

    /** 이 사용자가 가장 최근에 연결한 서버들을, 최신순으로 반환한다. */
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
