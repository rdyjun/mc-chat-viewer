import "dotenv/config";
import express from "express";
import { WebSocketServer, WebSocket } from "ws";
import { createServer } from "http";
import path from "path";
import { createSession, getSession, listSessions, onSessionEvent } from "./sessions";

const WEB_PORT = Number(process.env.WEB_PORT ?? 3000);

const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, "..", "public")));

app.post("/api/sessions", (req, res) => {
  const { host, port, version, email } = req.body ?? {};
  if (!host || !email) {
    res.status(400).json({ error: "host and email are required" });
    return;
  }
  const session = createSession(String(host), Number(port) || 25565, version || undefined, String(email));
  res.status(201).json({ id: session.id });
});

app.get("/api/sessions", (_req, res) => {
  res.json(
    listSessions().map((s) => ({ id: s.id, host: s.host, port: s.port, status: s.status }))
  );
});

app.get("/api/sessions/:id", (req, res) => {
  const session = getSession(req.params.id);
  if (!session) {
    res.status(404).json({ error: "session not found" });
    return;
  }
  res.json(session);
});

const httpServer = createServer(app);
const wss = new WebSocketServer({ server: httpServer });

wss.on("connection", (socket, req) => {
  const url = new URL(req.url ?? "", "http://localhost");
  const sessionId = url.searchParams.get("sessionId");
  if (!sessionId) {
    socket.close(1008, "sessionId query param required");
    return;
  }
  const session = getSession(sessionId);
  if (!session) {
    socket.close(1008, "unknown sessionId");
    return;
  }

  socket.send(
    JSON.stringify({
      type: "init",
      status: session.status,
      msaCode: session.msaCode,
      messages: session.messages,
    })
  );

  const unsubscribe = onSessionEvent((eventSessionId, payload) => {
    if (eventSessionId !== sessionId) return;
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(payload));
    }
  });

  socket.on("close", unsubscribe);
});

httpServer.listen(WEB_PORT, () => {
  console.log(`Dashboard: http://localhost:${WEB_PORT}`);
});
