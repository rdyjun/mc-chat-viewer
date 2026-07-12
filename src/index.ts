import "dotenv/config";
import express from "express";
import { WebSocketServer, WebSocket } from "ws";
import { createServer } from "http";
import path from "path";
import { connectBot, ChatMessage } from "./bot";

const MC_SERVER_HOST = process.env.MC_SERVER_HOST;
const MC_SERVER_PORT = Number(process.env.MC_SERVER_PORT ?? 25565);
const MC_SERVER_VERSION = process.env.MC_SERVER_VERSION || undefined;
const MC_ACCOUNT_EMAIL = process.env.MC_ACCOUNT_EMAIL;
const WEB_PORT = Number(process.env.WEB_PORT ?? 3000);

if (!MC_SERVER_HOST || !MC_ACCOUNT_EMAIL) {
  console.error(
    "Missing MC_SERVER_HOST or MC_ACCOUNT_EMAIL — copy .env.example to .env and fill it in."
  );
  process.exit(1);
}

const app = express();
app.use(express.static(path.join(__dirname, "..", "public")));

const httpServer = createServer(app);
const wss = new WebSocketServer({ server: httpServer });

const recentMessages: ChatMessage[] = [];
const MAX_HISTORY = 200;

function broadcast(payload: unknown) {
  const data = JSON.stringify(payload);
  wss.clients.forEach((client) => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(data);
    }
  });
}

wss.on("connection", (socket) => {
  socket.send(JSON.stringify({ type: "history", messages: recentMessages }));
});

connectBot(
  MC_SERVER_HOST,
  MC_SERVER_PORT,
  MC_SERVER_VERSION,
  MC_ACCOUNT_EMAIL,
  (msg) => {
    recentMessages.push(msg);
    if (recentMessages.length > MAX_HISTORY) {
      recentMessages.shift();
    }
    broadcast({ type: "chat", message: msg });
  },
  (status) => {
    console.log(`[bot] ${status}`);
    broadcast({ type: "status", status });
  }
);

httpServer.listen(WEB_PORT, () => {
  console.log(`Dashboard: http://localhost:${WEB_PORT}`);
});
