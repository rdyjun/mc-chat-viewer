import "dotenv/config";
import express from "express";
import cookieParser from "cookie-parser";
import { WebSocketServer, WebSocket } from "ws";
import { createServer, IncomingMessage } from "http";
import net from "net";
import path from "path";
import {
  getAccountState,
  onAccountEvent,
  tryResumeSession,
  createLoginState,
  consumeLoginState,
  getMicrosoftLoginUrl,
  completeMicrosoftLogin,
} from "./account";
import { upsertUser, isServerOwnedByUser, recentServersForUser } from "./db";
import { pingServer } from "./protocol/serverPing";
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

app.get("/api/account/login/microsoft", async (_req, res) => {
  try {
    const state = createLoginState();
    const url = await getMicrosoftLoginUrl(state);
    res.redirect(url);
  } catch (err: any) {
    res.status(500).send(`Microsoft login isn't configured: ${err.message}`);
  }
});

app.get("/api/account/callback", async (req, res) => {
  const { code, state, error, error_description: errorDescription } = req.query;
  if (error) {
    res.redirect("/?loginError=" + encodeURIComponent(String(errorDescription ?? error)));
    return;
  }
  if (!consumeLoginState(typeof state === "string" ? state : undefined) || typeof code !== "string") {
    res.status(400).send("Invalid or expired login attempt. Please try signing in again.");
    return;
  }
  // Don't await: the actual token/Xbox Live/Minecraft exchange happens in the background and
  // the frontend picks up progress via the "account" websocket event, same as before.
  completeMicrosoftLogin(code);
  res.redirect("/");
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

// Home tab's personal "최근 접속 서버" — this one *is* scoped per-user (it's each user's own
// history), so it's empty for logged-out visitors instead of leaking anyone else's activity.
app.get("/api/servers/recent", (req, res) => {
  const uid = (req as any).uid as string | undefined;
  res.json(uid ? recentServersForUser(uid, 5) : []);
});

// True for hostnames/IPs that only make sense on our own private network — blocking these
// keeps this public, unauthenticated endpoint from being used as an SSRF probe against
// internal infrastructure (it can still reach arbitrary *public* Minecraft servers, which is
// the point of the feature).
function isPrivateHost(host: string): boolean {
  const h = host.toLowerCase();
  if (h === "localhost" || h.endsWith(".local")) return true;
  const ip = net.isIP(h) ? h : null;
  if (!ip) return false;
  return (
    /^127\./.test(ip) ||
    /^10\./.test(ip) ||
    /^192\.168\./.test(ip) ||
    /^169\.254\./.test(ip) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(ip) ||
    ip === "::1"
  );
}

// Public: Server List Ping (no login required — this is the same loginless status query real
// launchers use to show player count/MOTD before joining), used by Home's recommended/top
// server cards and the 서버 목록 tab.
app.get("/api/ping", async (req, res) => {
  const host = String(req.query.host ?? "");
  const port = Number(req.query.port);
  if (!host || !Number.isInteger(port) || port < 1 || port > 65535) {
    res.status(400).json({ error: "host and a valid port are required" });
    return;
  }
  if (isPrivateHost(host)) {
    res.status(400).json({ error: "cannot ping private/internal hosts" });
    return;
  }
  res.json(await pingServer(host, port));
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
    await connectServer(req.params.id, uid);
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
