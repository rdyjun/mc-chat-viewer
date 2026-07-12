import { randomUUID } from "crypto";
import { connectBot, ChatMessage, MsaCode } from "./bot";

export interface Session {
  id: string;
  host: string;
  port: number;
  status: string;
  msaCode: MsaCode | null;
  messages: ChatMessage[];
}

const MAX_HISTORY = 200;

const sessions = new Map<string, Session>();
type Listener = (sessionId: string, payload: unknown) => void;
const listeners = new Set<Listener>();

export function onSessionEvent(listener: Listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function emit(sessionId: string, payload: unknown) {
  listeners.forEach((l) => l(sessionId, payload));
}

export function createSession(
  host: string,
  port: number,
  version: string | undefined,
  email: string
): Session {
  const id = randomUUID();
  const session: Session = { id, host, port, status: "connecting", msaCode: null, messages: [] };
  sessions.set(id, session);

  connectBot(
    host,
    port,
    version,
    email,
    (msg) => {
      session.messages.push(msg);
      if (session.messages.length > MAX_HISTORY) {
        session.messages.shift();
      }
      emit(id, { type: "chat", message: msg });
    },
    (status) => {
      session.status = status;
      emit(id, { type: "status", status });
    },
    (code) => {
      session.msaCode = code;
      emit(id, { type: "msa-code", code });
    }
  );

  return session;
}

export function getSession(id: string): Session | undefined {
  return sessions.get(id);
}

export function listSessions(): Session[] {
  return Array.from(sessions.values());
}
