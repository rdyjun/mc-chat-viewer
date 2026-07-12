import { Authflow, Titles } from "prismarine-auth";
import { RawMcClient, ChatEvent } from "./protocol/rawClient";
import { resolveProtocolVersion } from "./protocol/protocolVersions";

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
  client: RawMcClient;
}

/**
 * Connects to the given server using the player's own Microsoft account, via a hand-rolled
 * protocol client (see ./protocol/rawClient.ts) instead of mineflayer/minecraft-protocol —
 * those packages' bundled version data doesn't cover this server's (very new,
 * calendar-versioned) release yet.
 *
 * `version` is either a known version string (see protocolVersions.ts) or a bare protocol
 * number. Auth still goes through prismarine-auth (unaffected by game version): its device-code
 * flow is relayed to `onMsaCode` instead of the console so a multi-user web app can surface it.
 */
export function connectBot(
  host: string,
  port: number,
  version: string,
  msaEmail: string,
  onChat: (msg: ChatMessage) => void,
  onStatus: (status: string) => void,
  onMsaCode: (code: MsaCode) => void
): BotHandle {
  const protocolVersion = resolveProtocolVersion(version);
  const flow = new Authflow(msaEmail, "./nmp-cache", { authTitle: Titles.MinecraftJava, flow: "msal" }, onMsaCode);

  const handle: Partial<BotHandle> = {};

  onStatus("Authenticating with Microsoft...");
  flow
    .getMinecraftJavaToken({ fetchProfile: true })
    .then(({ token, profile }) => {
      const p = profile as { id: string; name: string };
      onStatus(`Authenticated as ${p.name}, connecting to ${host}:${port}...`);

      const client = new RawMcClient({
        host,
        port,
        protocolVersion,
        accessToken: token,
        profile: { id: p.id, name: p.name },
      });
      handle.client = client;

      client.on("status", onStatus);
      client.on("chat", (evt: ChatEvent) => {
        onChat({ username: p.name, message: evt.text, timestamp: Date.now() });
      });
      client.connect();
    })
    .catch((err) => onStatus(`Auth failed: ${err.message}`));

  return handle as BotHandle;
}
