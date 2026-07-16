import "dotenv/config";
import express from "express";
import cookieParser from "cookie-parser";
import { WebSocketServer, WebSocket } from "ws";
import { createServer, IncomingMessage } from "http";
import path from "path";
import { getAccountState, onAccountEvent, startLogin, tryResumeSession } from "./account";
import { upsertUser, isServerOwnedByUser } from "./db";
import {
  addServer,
  connectServer,
  getServer,
  listServers,
  onServerEvent,
  sendChatToServer,
  NotConnectedError,
  NotLoggedInError,
} from "./servers";

const WEB_PORT = Number(process.env.WEB_PORT ?? 3000);
const UID_COOKIE = "uid";
const UID_COOKIE_MAX_AGE = 1000 * 60 * 60 * 24 * 365;

const app = express();
app.use(express.json());
app.use(cookieParser());
app.use(express.static(path.join(__dirname, "..", "public")));

// Resolves the requester's MSA id: prefer the logged-in account's id (so the very first
// request after login already works, before the Set-Cookie round-trips to the browser),
// falling back to whatever "uid" cookie a previous visit already left behind.
app.use((req, res, next) => {
  const account = getAccountState();
  let uid: string | undefined = req.cookies?.[UID_COOKIE];
  if (account.status === "logged-in" && account.profile) {
    upsertUser(account.profile.id);
    if (uid !== account.profile.id) {
      uid = account.profile.id;
      res.cookie(UID_COOKIE, uid, { httpOnly: true, sameSite: "lax", maxAge: UID_COOKIE_MAX_AGE });
    }
  }
  (req as any).uid = uid;
  next();
});

function parseUidFromCookieHeader(header?: string): string | undefined {
  if (!header) return undefined;
  const match = header.match(/(?:^|;\s*)uid=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : undefined;
}

app.get("/api/account", (_req, res) => {
  res.json(getAccountState());
});

app.post("/api/account/login", (req, res) => {
  const { email } = req.body ?? {};
  if (!email) {
    res.status(400).json({ error: "email is required" });
    return;
  }
  startLogin(String(email));
  res.status(202).json({ ok: true });
});

function summarizeServer(s: ReturnType<typeof getServer>) {
  if (!s) return null;
  return {
    id: s.id,
    host: s.host,
    port: s.port,
    version: s.version,
    status: s.status,
    phase: s.phase,
    connected: s.connected,
  };
}

app.get("/api/servers", (req, res) => {
  const uid = (req as any).uid as string | undefined;
  res.json(uid ? listServers(uid).map(summarizeServer) : []);
});

app.post("/api/servers", (req, res) => {
  const uid = (req as any).uid as string | undefined;
  if (!uid) {
    res.status(409).json({ error: "Log in before adding a server", code: "not-logged-in" });
    return;
  }
  const { host, port, version } = req.body ?? {};
  if (!host || !version) {
    res.status(400).json({ error: "host and version are required" });
    return;
  }
  try {
    const server = addServer(String(host), Number(port) || 25565, String(version), uid);
    res.status(201).json(summarizeServer(server));
  } catch (err: any) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/servers/:id", (req, res) => {
  const uid = (req as any).uid as string | undefined;
  const server = uid ? getServer(req.params.id, uid) : undefined;
  if (!server) {
    res.status(404).json({ error: "server not found" });
    return;
  }
  res.json({ ...summarizeServer(server), messages: server.messages, statusHistory: server.statusHistory });
});

app.post("/api/servers/:id/connect", async (req, res) => {
  const uid = (req as any).uid as string | undefined;
  if (!uid || !getServer(req.params.id, uid)) {
    res.status(404).json({ error: "server not found" });
    return;
  }
  try {
    await connectServer(req.params.id);
    res.json({ ok: true });
  } catch (err) {
    if (err instanceof NotLoggedInError) {
      res.status(409).json({ error: err.message, code: "not-logged-in" });
      return;
    }
    res.status(400).json({ error: (err as Error).message });
  }
});

app.post("/api/servers/:id/chat", (req, res) => {
  const uid = (req as any).uid as string | undefined;
  if (!uid || !getServer(req.params.id, uid)) {
    res.status(404).json({ error: "server not found" });
    return;
  }
  const { message } = req.body ?? {};
  if (!message) {
    res.status(400).json({ error: "message is required" });
    return;
  }
  try {
    sendChatToServer(req.params.id, String(message));
    res.json({ ok: true });
  } catch (err) {
    if (err instanceof NotConnectedError) {
      res.status(409).json({ error: err.message, code: "not-connected" });
      return;
    }
    res.status(400).json({ error: (err as Error).message });
  }
});

const httpServer = createServer(app);
const wss = new WebSocketServer({ server: httpServer });

wss.on("connection", (socket, req: IncomingMessage) => {
  const uid = parseUidFromCookieHeader(req.headers.cookie);

  socket.send(JSON.stringify({ type: "account", account: getAccountState() }));
  socket.send(JSON.stringify({ type: "servers", servers: uid ? listServers(uid).map(summarizeServer) : [] }));

  const offAccount = onAccountEvent(() => {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: "account", account: getAccountState() }));
    }
  });
  const offServer = onServerEvent((serverId, payload) => {
    if (socket.readyState !== WebSocket.OPEN) return;
    if (!uid || !isServerOwnedByUser(serverId, uid)) return;
    socket.send(JSON.stringify({ type: "server-event", serverId, payload }));
  });

  socket.on("close", () => {
    offAccount();
    offServer();
  });
});

httpServer.listen(WEB_PORT, () => {
  console.log(`Dashboard: http://localhost:${WEB_PORT}`);
  tryResumeSession();
});
