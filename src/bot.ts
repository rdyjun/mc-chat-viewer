import mineflayer, { Bot } from "mineflayer";

export interface ChatMessage {
  username: string;
  message: string;
  timestamp: number;
}

export interface MsaCode {
  message: string;
  user_code: string;
  verification_uri: string;
}

export interface BotHandle {
  bot: Bot;
}

/**
 * Connects to the given server using the player's own Microsoft account.
 * mineflayer/prismarine-auth handles the OAuth device-code flow. Instead of letting it print
 * the login URL/code to the server's console (useless for a multi-user web app), `onMsaCode`
 * is forwarded through mineflayer -> minecraft-protocol -> prismarine-auth so the code can be
 * surfaced in the web dashboard instead. Tokens are cached under profilesFolder afterward
 * (gitignored) so future sessions for the same account reconnect without prompting again.
 */
export function connectBot(
  host: string,
  port: number,
  version: string | undefined,
  msaEmail: string,
  onChat: (msg: ChatMessage) => void,
  onStatus: (status: string) => void,
  onMsaCode: (code: MsaCode) => void
): BotHandle {
  const bot = mineflayer.createBot({
    host,
    port,
    version: version || undefined,
    auth: "microsoft",
    username: msaEmail, // Microsoft account email; the device-code flow authenticates it
    onMsaCode,
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
