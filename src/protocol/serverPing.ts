import net from "net";
import { PacketReader, PacketWriter } from "./packetIO";
import { readVarInt, writeVarInt } from "./varint";

export interface PingResult {
  online: boolean;
  playersOnline?: number;
  playersMax?: number;
  motd?: string;
  version?: string;
  error?: string;
}

const PING_TIMEOUT_MS = 3000;
// The status handshake only echoes this back inside the JSON response (as the client's
// requested version) — any recent protocol number works regardless of the server's actual
// version, since Server List Ping predates per-version protocol negotiation for status queries.
const HANDSHAKE_PROTOCOL_VERSION = 767;

function writeFrame(socket: net.Socket, writer: PacketWriter) {
  const body = writer.build();
  socket.write(Buffer.concat([writeVarInt(body.length), body]));
}

function extractMotd(description: unknown): string | undefined {
  if (typeof description === "string") return description;
  if (description && typeof description === "object" && "text" in (description as any)) {
    return String((description as any).text);
  }
  return undefined;
}

/**
 * Server List Ping: the same lightweight, loginless status query real launchers use to show a
 * server's player count/MOTD in the multiplayer list. Resolves with `{ online: false }` (never
 * rejects) on any failure — a server being unreachable is an expected, common outcome here.
 */
export function pingServer(host: string, port: number): Promise<PingResult> {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    let recvBuffer = Buffer.alloc(0);
    let settled = false;

    const finish = (result: PingResult) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(result);
    };

    socket.setTimeout(PING_TIMEOUT_MS, () => finish({ online: false, error: "timed out" }));
    socket.on("error", (err) => finish({ online: false, error: err.message }));

    socket.connect(port, host, () => {
      const handshake = new PacketWriter(0x00)
        .writeVarInt(HANDSHAKE_PROTOCOL_VERSION)
        .writeString(host)
        .writeUnsignedShort(port)
        .writeVarInt(1); // next state: status
      writeFrame(socket, handshake);
      writeFrame(socket, new PacketWriter(0x00)); // Status Request, empty body
    });

    socket.on("data", (chunk) => {
      recvBuffer = Buffer.concat([recvBuffer, chunk]);
      try {
        const { value: length, length: lenSize } = readVarInt(recvBuffer, 0);
        if (recvBuffer.length < lenSize + length) return; // wait for the rest of the frame

        const body = recvBuffer.subarray(lenSize, lenSize + length);
        const { value: packetId, length: idLen } = readVarInt(body, 0);
        if (packetId !== 0x00) {
          finish({ online: false, error: `unexpected packet id ${packetId}` });
          return;
        }
        const reader = new PacketReader(body.subarray(idLen));
        const json = JSON.parse(reader.readString());
        finish({
          online: true,
          playersOnline: json.players?.online,
          playersMax: json.players?.max,
          motd: extractMotd(json.description),
          version: json.version?.name,
        });
      } catch {
        // Either not enough bytes yet, or a malformed response — keep buffering until the
        // socket timeout fires rather than guessing which case this was.
      }
    });
  });
}
