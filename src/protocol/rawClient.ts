import { EventEmitter } from "events";
import net from "net";
import zlib from "zlib";
import { PacketReader, PacketWriter } from "./packetIO";
import { readVarInt, writeVarInt } from "./varint";
import {
  generateSharedSecret,
  joinMojangSession,
  makeCipherStreams,
  minecraftServerHash,
  rsaEncrypt,
} from "./crypto";
import { readNetworkNbt } from "./nbt";
import { textComponentToPlainText } from "./textComponent";

type ConnState = "handshake" | "login" | "configuration" | "play";

const PACKET_IDS = {
  login: {
    clientbound: { Disconnect: 0, EncryptionRequest: 1, LoginSuccess: 2, SetCompression: 3 },
    serverbound: { LoginStart: 0, EncryptionResponse: 1, LoginAcknowledged: 3 },
  },
  configuration: {
    clientbound: { CookieRequest: 0, Disconnect: 2, FinishConfiguration: 3, Ping: 5, SelectKnownPacks: 14 },
    serverbound: {
      ClientInformation: 0,
      PluginMessage: 2,
      AcknowledgeFinishConfiguration: 3,
      Pong: 5,
      KnownPacks: 7,
    },
  },
  play: {
    clientbound: { DisguisedChatMessage: 33, PlayerChatMessage: 65, KeepAlive: 44, SystemChatMessage: 121 },
    serverbound: { KeepAlive: 28 },
  },
} as const;

export interface ChatEvent {
  text: string;
  raw: "system" | "player" | "disguised";
}

export interface RawClientOptions {
  host: string;
  port: number;
  protocolVersion: number;
  accessToken: string;
  profile: { id: string; name: string }; // id: 32-char hex, no dashes
}

/**
 * Minimal hand-rolled Minecraft Java Edition client: just enough to authenticate, join, stay
 * connected (keep-alive), and read chat. No world/entity/inventory state is tracked. Written
 * because the mineflayer/minecraft-protocol npm packages don't yet ship protocol data for very
 * new (calendar-versioned) server versions — see rawClient's callers for context.
 *
 * Any packet type not explicitly handled below is still safely skipped: the outer
 * length-prefix framing tells us exactly how many bytes each packet occupies regardless of
 * whether we understand its contents, so an unrecognized or mis-parsed packet can never
 * desync the stream — worst case we just miss/garble that one packet's meaning.
 */

/**
 * Node wraps "localhost" connection failures in an AggregateError (it tries both the IPv4 and
 * IPv6 resolutions and both fail), whose own .message is empty — the useful text is in
 * .errors[]. Unwrap that so status messages are never a blank "Socket error: ".
 */
function describeError(err: Error): string {
  const withErrors = err as Error & { errors?: Error[] };
  if (withErrors.errors?.length) {
    return withErrors.errors.map((e) => e.message).join("; ");
  }
  return err.message || err.toString();
}

export class RawMcClient extends EventEmitter {
  private socket: net.Socket;
  private state: ConnState = "handshake";
  private recvBuffer = Buffer.alloc(0);
  private compressionThreshold = -1;
  private cipher: ReturnType<typeof makeCipherStreams>["cipher"] | null = null;
  private decipher: ReturnType<typeof makeCipherStreams>["decipher"] | null = null;

  constructor(private opts: RawClientOptions) {
    super();
    this.socket = new net.Socket();
  }

  connect() {
    this.socket.on("data", (chunk) => this.onData(chunk));
    this.socket.on("error", (err) => this.emit("status", `Socket error: ${describeError(err)}`));
    this.socket.on("close", () => {
      this.emit("status", "Connection closed");
      this.emit("closed"); // distinct from "status": tells listeners a retry is now safe
    });

    this.socket.connect(this.opts.port, this.opts.host, () => {
      this.emit("status", "TCP connected, sending handshake");
      this.sendHandshake();
      this.sendLoginStart();
    });
  }

  private onData(chunk: Buffer) {
    const plain = this.decipher ? this.decipher.update(chunk) : chunk;
    this.recvBuffer = Buffer.concat([this.recvBuffer, plain]);
    this.drainPackets();
  }

  private drainPackets() {
    for (;;) {
      let lengthField;
      try {
        lengthField = readVarInt(this.recvBuffer, 0);
      } catch {
        return; // not enough bytes yet for the length varint
      }
      const totalNeeded = lengthField.length + lengthField.value;
      if (this.recvBuffer.length < totalNeeded) return; // wait for more data

      const body = this.recvBuffer.subarray(lengthField.length, totalNeeded);
      this.recvBuffer = this.recvBuffer.subarray(totalNeeded);
      this.handleFrame(body);
    }
  }

  private handleFrame(body: Buffer) {
    let packetBytes = body;
    if (this.compressionThreshold >= 0) {
      const { value: dataLength, length } = readVarInt(body, 0);
      const rest = body.subarray(length);
      packetBytes = dataLength === 0 ? rest : zlib.inflateSync(rest);
    }
    const { value: packetId, length: idLen } = readVarInt(packetBytes, 0);
    const reader = new PacketReader(packetBytes.subarray(idLen));
    try {
      this.handlePacket(packetId, reader);
    } catch (err: any) {
      this.emit("status", `Failed to parse packet id=${packetId} in state=${this.state}: ${err.message}`);
    }
  }

  // ---- outgoing ----

  private sendFrame(writer: PacketWriter) {
    let body = writer.build();
    if (this.compressionThreshold >= 0) {
      if (body.length >= this.compressionThreshold) {
        const compressed = zlib.deflateSync(body);
        body = Buffer.concat([writeVarInt(body.length), compressed]);
      } else {
        body = Buffer.concat([writeVarInt(0), body]);
      }
    }
    const framed = Buffer.concat([writeVarInt(body.length), body]);
    this.socket.write(this.cipher ? this.cipher.update(framed) : framed);
  }

  private sendHandshake() {
    const w = new PacketWriter(0x00)
      .writeVarInt(this.opts.protocolVersion)
      .writeString(this.opts.host)
      .writeUnsignedShort(this.opts.port)
      .writeVarInt(2); // next state: login
    this.sendFrame(w);
  }

  private sendLoginStart() {
    const w = new PacketWriter(PACKET_IDS.login.serverbound.LoginStart)
      .writeString(this.opts.profile.name)
      .writeUUID(this.opts.profile.id);
    this.sendFrame(w);
  }

  private async handleEncryptionRequest(reader: PacketReader) {
    const serverId = reader.readString();
    const publicKey = reader.readPrefixedBytes();
    const verifyToken = reader.readPrefixedBytes();

    const sharedSecret = generateSharedSecret();
    const serverHash = minecraftServerHash(serverId, sharedSecret, Buffer.from(publicKey));
    this.emit("status", "Verifying session with Mojang...");
    await joinMojangSession(this.opts.accessToken, this.opts.profile.id, serverHash);

    const encryptedSecret = rsaEncrypt(Buffer.from(publicKey), sharedSecret);
    const encryptedVerify = rsaEncrypt(Buffer.from(publicKey), Buffer.from(verifyToken));

    const w = new PacketWriter(PACKET_IDS.login.serverbound.EncryptionResponse)
      .writePrefixedBytes(encryptedSecret)
      .writePrefixedBytes(encryptedVerify);
    this.sendFrame(w); // sent unencrypted; encryption turns on immediately after

    const { cipher, decipher } = makeCipherStreams(sharedSecret);
    this.cipher = cipher;
    this.decipher = decipher;
    this.emit("status", "Encryption enabled");
  }

  private sendLoginAcknowledged() {
    this.sendFrame(new PacketWriter(PACKET_IDS.login.serverbound.LoginAcknowledged));
    this.state = "configuration";
    this.emit("status", "Entered configuration state");
    this.sendClientInformation();
  }

  private sendClientInformation() {
    const w = new PacketWriter(PACKET_IDS.configuration.serverbound.ClientInformation)
      .writeString("en_US")
      .writeRaw(Buffer.from([8])) // view distance (byte)
      .writeVarInt(0) // chat mode: enabled
      .writeBoolean(true) // chat colors
      .writeRaw(Buffer.from([0x7f])) // displayed skin parts: all
      .writeVarInt(1) // main hand: right
      .writeBoolean(false) // text filtering
      .writeBoolean(true); // allow server listings
    this.sendFrame(w);
  }

  private sendKnownPacksEmpty() {
    const w = new PacketWriter(PACKET_IDS.configuration.serverbound.KnownPacks).writeVarInt(0);
    this.sendFrame(w);
  }

  private sendFinishConfigurationAck() {
    this.sendFrame(new PacketWriter(PACKET_IDS.configuration.serverbound.AcknowledgeFinishConfiguration));
    this.state = "play";
    this.emit("status", "Entered play state");
  }

  private sendConfigurationPong(payload: Buffer) {
    this.sendFrame(new PacketWriter(PACKET_IDS.configuration.serverbound.Pong).writeRaw(payload));
  }

  private sendPlayKeepAlive(id: bigint) {
    this.sendFrame(new PacketWriter(PACKET_IDS.play.serverbound.KeepAlive).writeLong(id));
  }

  // ---- incoming dispatch ----

  private handlePacket(id: number, reader: PacketReader) {
    switch (this.state) {
      case "login":
        return this.handleLoginPacket(id, reader);
      case "configuration":
        return this.handleConfigurationPacket(id, reader);
      case "play":
        return this.handlePlayPacket(id, reader);
    }
  }

  private handleLoginPacket(id: number, reader: PacketReader) {
    const P = PACKET_IDS.login.clientbound;
    if (id === P.Disconnect) {
      this.emit("status", `Disconnected during login: ${reader.readString()}`);
    } else if (id === P.EncryptionRequest) {
      this.handleEncryptionRequest(reader).catch((err) =>
        this.emit("status", `Encryption/session error: ${err.message}`)
      );
    } else if (id === P.SetCompression) {
      this.compressionThreshold = reader.readVarInt();
      this.emit("status", `Compression enabled (threshold ${this.compressionThreshold})`);
    } else if (id === P.LoginSuccess) {
      this.emit("status", "Login success");
      this.sendLoginAcknowledged();
    }
  }

  private handleConfigurationPacket(id: number, reader: PacketReader) {
    const P = PACKET_IDS.configuration.clientbound;
    if (id === P.Disconnect) {
      this.emit("status", `Disconnected during configuration: ${textComponentToPlainText(readNetworkNbt(reader.readRest(), 0).value)}`);
    } else if (id === P.FinishConfiguration) {
      this.sendFinishConfigurationAck();
    } else if (id === P.Ping) {
      this.sendConfigurationPong(reader.readRest());
    } else if (id === P.SelectKnownPacks) {
      this.sendKnownPacksEmpty();
    }
    // everything else (registry data, tags, feature flags, plugin messages, cookie requests)
    // is safely ignored — framing already told us how many bytes it occupied.
  }

  private handlePlayPacket(id: number, reader: PacketReader) {
    const P = PACKET_IDS.play.clientbound;
    if (id === P.KeepAlive) {
      this.sendPlayKeepAlive(reader.readLong());
    } else if (id === P.SystemChatMessage) {
      const { value } = readNetworkNbt(reader.readRest(), 0);
      this.emit("chat", { text: textComponentToPlainText(value), raw: "system" } as ChatEvent);
    } else if (id === P.DisguisedChatMessage) {
      const { value } = readNetworkNbt(reader.readRest(), 0);
      this.emit("chat", { text: textComponentToPlainText(value), raw: "disguised" } as ChatEvent);
    } else if (id === P.PlayerChatMessage) {
      this.handlePlayerChatMessage(reader);
    }
  }

  /**
   * Best-effort: the exact field layout for this version couldn't be confirmed against
   * documentation (only ~776 docs were available, this server runs 775). Wrapped so a layout
   * mismatch degrades to a placeholder instead of corrupting the read loop.
   */
  private handlePlayerChatMessage(reader: PacketReader) {
    try {
      reader.readUUID(); // sender
      reader.readVarInt(); // index
      const hasSignature = reader.readBoolean();
      if (hasSignature) reader.readFixedBytes(256); // fixed-length signature, no length prefix
      const message = reader.readString();
      this.emit("chat", { text: message, raw: "player" } as ChatEvent);
    } catch {
      this.emit("chat", { text: "[플레이어 채팅 메시지 — 파싱 실패]", raw: "player" } as ChatEvent);
    }
  }
}
