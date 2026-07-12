import { randomUUID } from "crypto";
import { RawMcClient, ChatEvent } from "./protocol/rawClient";
import { resolveProtocolVersion } from "./protocol/protocolVersions";
import { getAccessToken } from "./account";

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
  messages: ChatMessage[];
  client?: RawMcClient;
}

const MAX_HISTORY = 200;
const servers = new Map<string, ServerConfig>();

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
  const entry: StatusEntry = { status, timestamp: Date.now() };
  server.statusHistory.push(entry);
  if (server.statusHistory.length > MAX_HISTORY) server.statusHistory.shift();
  console.log(`[${server.host}:${server.port}] ${status}`);
  emit(server.id, { type: "status", status, phase: server.phase, timestamp: entry.timestamp, logged: true });
}

/** Adds a server to the saved list. Does not connect — call connectServer() for that. */
export function addServer(host: string, port: number, version: string): ServerConfig {
  resolveProtocolVersion(version); // validate early so a bad version fails at add-time, not connect-time
  const id = randomUUID();
  const server: ServerConfig = {
    id,
    host,
    port,
    version,
    status: "idle",
    phase: "idle",
    statusHistory: [],
    messages: [],
  };
  servers.set(id, server);
  return server;
}

export function listServers(): ServerConfig[] {
  return Array.from(servers.values());
}

export function getServer(id: string): ServerConfig | undefined {
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
export function connectServer(id: string): void {
  const server = servers.get(id);
  if (!server) throw new Error("server not found");
  if (server.phase === "active") return; // already connecting/connected, nothing to do

  const account = getAccessToken();
  if (!account) throw new NotLoggedInError();

  const protocolVersion = resolveProtocolVersion(server.version);
  const client = new RawMcClient({
    host: server.host,
    port: server.port,
    protocolVersion,
    accessToken: account.token,
    profile: account.profile,
  });
  server.client = client;
  server.phase = "active";
  setStatus(server, "connecting");

  client.on("status", (status: string) => setStatus(server, status));
  client.on("closed", () => {
    server.phase = "closed"; // allows a fresh connectServer() call to retry
    // re-broadcast (no new history entry) so the dashboard's Connect button re-enables
    emit(id, { type: "status", status: server.status, phase: server.phase, timestamp: Date.now(), logged: false });
  });
  client.on("chat", (evt: ChatEvent) => {
    const msg: ChatMessage = { username: account.profile.name, message: evt.text, timestamp: Date.now() };
    server.messages.push(msg);
    if (server.messages.length > MAX_HISTORY) server.messages.shift();
    emit(id, { type: "chat", message: msg });
  });
  client.connect();
}
