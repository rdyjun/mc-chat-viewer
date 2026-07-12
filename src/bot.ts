import mineflayer, { Bot } from "mineflayer";

export interface ChatMessage {
  username: string;
  message: string;
  timestamp: number;
}

export interface BotHandle {
  bot: Bot;
}

/**
 * Connects to the configured server using the player's own Microsoft account.
 * mineflayer/prismarine-auth handles the OAuth device-code flow: on first run it prints a
 * one-time URL + code to the console for the user to approve in a browser, then caches the
 * resulting tokens locally (see .gitignore) so subsequent runs reconnect silently.
 */
export function connectBot(
  host: string,
  port: number,
  version: string | undefined,
  msaEmail: string,
  onChat: (msg: ChatMessage) => void,
  onStatus: (status: string) => void
): BotHandle {
  const bot = mineflayer.createBot({
    host,
    port,
    version: version || undefined,
    auth: "microsoft",
    username: msaEmail, // Microsoft account email; the device-code flow authenticates it
  });

  bot.on("login", () => onStatus(`Logged in as ${bot.username}`));
  bot.on("spawn", () => onStatus("Spawned into the world"));
  bot.on("end", (reason) => onStatus(`Disconnected: ${reason}`));
  bot.on("kicked", (reason) => onStatus(`Kicked: ${reason}`));
  bot.on("error", (err) => onStatus(`Error: ${err.message}`));

  // "message" fires for chat, system messages, and death/join/leave broadcasts.
  bot.on("message", (jsonMsg) => {
    onChat({
      username: bot.username ?? "?",
      message: jsonMsg.toString(),
      timestamp: Date.now(),
    });
  });

  return { bot };
}
