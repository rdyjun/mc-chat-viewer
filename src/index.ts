import "dotenv/config";
import express from "express";
import { WebSocketServer, WebSocket } from "ws";
import { createServer } from "http";
import path from "path";
import { getAccountState, onAccountEvent, startLogin, tryResumeSession } from "./account";
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

const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, "..", "public")));

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

app.get("/api/servers", (_req, res) => {
  res.json(listServers().map(summarizeServer));
});

app.post("/api/servers", (req, res) => {
  const { host, port, version } = req.body ?? {};
  if (!host || !version) {
    res.status(400).json({ error: "host and version are required" });
    return;
  }
  try {
    const server = addServer(String(host), Number(port) || 25565, String(version));
    res.status(201).json(summarizeServer(server));
  } catch (err: any) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/servers/:id", (req, res) => {
  const server = getServer(req.params.id);
  if (!server) {
    res.status(404).json({ error: "server not found" });
    return;
  }
  res.json({ ...summarizeServer(server), messages: server.messages, statusHistory: server.statusHistory });
});

app.post("/api/servers/:id/connect", (req, res) => {
  try {
    connectServer(req.params.id);
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

wss.on("connection", (socket) => {
  socket.send(JSON.stringify({ type: "account", account: getAccountState() }));
  socket.send(JSON.stringify({ type: "servers", servers: listServers().map(summarizeServer) }));

  const offAccount = onAccountEvent(() => {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: "account", account: getAccountState() }));
    }
  });
  const offServer = onServerEvent((serverId, payload) => {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: "server-event", serverId, payload }));
    }
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
