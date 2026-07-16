import { randomUUID } from "crypto";
import { RawMcClient, ChatEvent } from "./protocol/rawClient";
import { resolveProtocolVersion } from "./protocol/protocolVersions";
import { getAccessToken, getSigningCertificate } from "./account";
import { insertServerRow, linkUserServer, listAllServerRows, listServerRowsForUser, isServerOwnedByUser } from "./db";

export interface ChatMessage {
  username: string;
  message: string;
  timestamp: number;
}

export interface StatusEntry {
  status: string;
  timestamp: number;
}

export interface ServerConfig {
  id: string;
  host: string;
  port: number;
  version: string;
  status: string; // free-text display status (from the client, or "idle" before any attempt)
  /** Coarse state driving whether a new connect attempt is allowed — `status` is just display text. */
  phase: "idle" | "active" | "closed";
  /** Every status change, timestamped — the single `status` field only ever shows the latest
   * one, which hides brief-but-important states (e.g. a socket error right before close). */
  statusHistory: StatusEntry[];
  /** True once the connection has actually reached the play state — distinct from `phase`,
   * which is just "is a connect attempt in flight" and stays "active" the whole time you're
   * happily connected (so the dashboard button alone can't tell "still connecting" from
   * "connected and staying that way"). */
  connected: boolean;
  messages: ChatMessage[];
  client?: RawMcClient;
}

const MAX_HISTORY = 200;
const servers = new Map<string, ServerConfig>();

// Rehydrate the in-memory registry from persisted rows on startup — runtime-only fields
// (status/messages/client) start fresh since a live socket can't survive a process restart.
for (const row of listAllServerRows()) {
  servers.set(row.id, {
    id: row.id,
    host: row.host,
    port: row.port,
    version: row.version,
    status: "idle",
    phase: "idle",
    statusHistory: [],
    connected: false,
    messages: [],
  });
}

type Listener = (serverId: string, payload: unknown) => void;
const listeners = new Set<Listener>();

export function onServerEvent(listener: Listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function emit(serverId: string, payload: unknown) {
  listeners.forEach((l) => l(serverId, payload));
}

function setStatus(server: ServerConfig, status: string) {
  server.status = status;
  server.connected = server.client?.isPlaying ?? false;
  const entry: StatusEntry = { status, timestamp: Date.now() };
  server.statusHistory.push(entry);
  if (server.statusHistory.length > MAX_HISTORY) server.statusHistory.shift();
  console.log(`[${server.host}:${server.port}] ${status}`);
  emit(server.id, {
    type: "status",
    status,
    phase: server.phase,
    connected: server.connected,
    timestamp: entry.timestamp,
    logged: true,
  });
}

/** Adds a server to the saved list, owned by `userId`. Does not connect — call connectServer() for that. */
export function addServer(host: string, port: number, version: string, userId: string): ServerConfig {
  resolveProtocolVersion(version); // validate early so a bad version fails at add-time, not connect-time
  const id = randomUUID();
  insertServerRow(id, host, port, version);
  linkUserServer(userId, id);
  const server: ServerConfig = {
    id,
    host,
    port,
    version,
    status: "idle",
    phase: "idle",
    statusHistory: [],
    connected: false,
    messages: [],
  };
  servers.set(id, server);
  return server;
}

/** Only the servers `userId` has added — the DB's user_servers mapping is the source of truth for ownership. */
export function listServers(userId: string): ServerConfig[] {
  return listServerRowsForUser(userId)
    .map((row) => servers.get(row.id))
    .filter((s): s is ServerConfig => !!s);
}

export function getServer(id: string, userId: string): ServerConfig | undefined {
  if (!isServerOwnedByUser(id, userId)) return undefined;
  return servers.get(id);
}

export class NotLoggedInError extends Error {
  constructor() {
    super("Log in with a Microsoft account before connecting to a server");
  }
}

export class NotConnectedError extends Error {
  constructor() {
    super("This server isn't connected yet");
  }
}

/** Sends a chat message to a server that's actually reached the play state. */
export function sendChatToServer(id: string, message: string): void {
  const server = servers.get(id);
  if (!server?.client?.isPlaying) throw new NotConnectedError();
  server.client.sendChat(message);
}

/** Connects a saved server using the currently logged-in account. Throws NotLoggedInError if none. */
export async function connectServer(id: string): Promise<void> {
  const server = servers.get(id);
  if (!server) throw new Error("server not found");
  if (server.phase === "active") return; // already connecting/connected, nothing to do

  const account = getAccessToken();
  if (!account) throw new NotLoggedInError();

  server.phase = "active"; // set synchronously, before any await, so a second call can't race in
  setStatus(server, "connecting");
  const certificate = (await getSigningCertificate()) ?? undefined;

  const protocolVersion = resolveProtocolVersion(server.version);
  const client = new RawMcClient({
    host: server.host,
    port: server.port,
    protocolVersion,
    accessToken: account.token,
    profile: account.profile,
    certificate,
  });
  server.client = client;

  client.on("status", (status: string) => setStatus(server, status));
  client.on("closed", () => {
    server.phase = "closed"; // allows a fresh connectServer() call to retry
    server.connected = false;
    // re-broadcast (no new history entry) so the dashboard's Connect button re-enables
    emit(id, {
      type: "status",
      status: server.status,
      phase: server.phase,
      connected: server.connected,
      timestamp: Date.now(),
      logged: false,
    });
  });
  client.on("chat", (evt: ChatEvent) => {
    const msg: ChatMessage = { username: account.profile.name, message: evt.text, timestamp: Date.now() };
    server.messages.push(msg);
    if (server.messages.length > MAX_HISTORY) server.messages.shift();
    emit(id, { type: "chat", message: msg });
  });
  client.connect();
}
