import { randomUUID } from "crypto";
import { RawMcClient, ChatEvent } from "./protocol/rawClient";
import { resolveProtocolVersion } from "./protocol/protocolVersions";
import { getAccessToken } from "./account";

export interface ChatMessage {
  username: string;
  message: string;
  timestamp: number;
}

export interface ServerConfig {
  id: string;
  host: string;
  port: number;
  version: string;
  status: string; // "idle" until first connect attempt, then free-text status from the client
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

/** Adds a server to the saved list. Does not connect — call connectServer() for that. */
export function addServer(host: string, port: number, version: string): ServerConfig {
  resolveProtocolVersion(version); // validate early so a bad version fails at add-time, not connect-time
  const id = randomUUID();
  const server: ServerConfig = { id, host, port, version, status: "idle", messages: [] };
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

/** Connects a saved server using the currently logged-in account. Throws NotLoggedInError if none. */
export function connectServer(id: string): void {
  const server = servers.get(id);
  if (!server) throw new Error("server not found");
  if (server.status !== "idle" && server.status !== "error" && !server.status.startsWith("Disconnected")) {
    return; // already connecting/connected, nothing to do
  }

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
  server.status = "connecting";
  emit(id, { type: "status", status: server.status });

  client.on("status", (status: string) => {
    server.status = status;
    emit(id, { type: "status", status });
  });
  client.on("chat", (evt: ChatEvent) => {
    const msg: ChatMessage = { username: account.profile.name, message: evt.text, timestamp: Date.now() };
    server.messages.push(msg);
    if (server.messages.length > MAX_HISTORY) server.messages.shift();
    emit(id, { type: "chat", message: msg });
  });
  client.connect();
}
