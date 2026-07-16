import path from "path";
import fs from "fs";
import Database from "better-sqlite3";

const DB_PATH = process.env.DB_PATH ?? "./data/app.db";
fs.mkdirSync(path.dirname(DB_PATH), { recursive: true });

export const db = new Database(DB_PATH);
db.pragma("journal_mode = WAL");

db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY
  );
  CREATE TABLE IF NOT EXISTS servers (
    id TEXT PRIMARY KEY,
    host TEXT NOT NULL,
    port INTEGER NOT NULL,
    version TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS user_servers (
    user_id TEXT NOT NULL REFERENCES users(id),
    server_id TEXT NOT NULL REFERENCES servers(id),
    PRIMARY KEY (user_id, server_id)
  );
  CREATE TABLE IF NOT EXISTS connection_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL,
    created_at INTEGER NOT NULL
  );
`);

export interface ServerRow {
  id: string;
  host: string;
  port: number;
  version: string;
}

export function upsertUser(userId: string): void {
  db.prepare("INSERT OR IGNORE INTO users (id) VALUES (?)").run(userId);
}

export function insertServerRow(id: string, host: string, port: number, version: string): void {
  db.prepare("INSERT INTO servers (id, host, port, version) VALUES (?, ?, ?, ?)").run(id, host, port, version);
}

export function linkUserServer(userId: string, serverId: string): void {
  db.prepare("INSERT OR IGNORE INTO user_servers (user_id, server_id) VALUES (?, ?)").run(userId, serverId);
}

export function listServerRowsForUser(userId: string): ServerRow[] {
  return db
    .prepare(
      `SELECT s.id, s.host, s.port, s.version
       FROM servers s
       JOIN user_servers us ON us.server_id = s.id
       WHERE us.user_id = ?`
    )
    .all(userId) as ServerRow[];
}

export function listAllServerRows(): ServerRow[] {
  return db.prepare("SELECT id, host, port, version FROM servers").all() as ServerRow[];
}

export function isServerOwnedByUser(serverId: string, userId: string): boolean {
  const row = db
    .prepare("SELECT 1 FROM user_servers WHERE server_id = ? AND user_id = ?")
    .get(serverId, userId);
  return !!row;
}

/**
 * Fire-and-forget: records a connect attempt for the "인기 서버" ranking. Never throws — a
 * logging hiccup should never be able to break someone's actual connect flow.
 */
export function logConnection(serverId: string, userId: string, host: string, port: number): void {
  setImmediate(() => {
    try {
      db.prepare(
        "INSERT INTO connection_logs (server_id, user_id, host, port, created_at) VALUES (?, ?, ?, ?, ?)"
      ).run(serverId, userId, host, port, Date.now());
    } catch (err) {
      console.warn("Failed to record connection log:", (err as Error).message);
    }
  });
}

export interface RecentServerEntry {
  id: string;
  host: string;
  port: number;
  lastConnectedAt: number;
}

/** This user's own most-recently-connected servers, most recent first. */
export function recentServersForUser(userId: string, limit: number): RecentServerEntry[] {
  return db
    .prepare(
      `SELECT server_id as id, host, port, MAX(created_at) as lastConnectedAt
       FROM connection_logs
       WHERE user_id = ?
       GROUP BY server_id
       ORDER BY lastConnectedAt DESC
       LIMIT ?`
    )
    .all(userId, limit) as RecentServerEntry[];
}
