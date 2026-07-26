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
import { PlayerCertificate } from "./mojangCertificates";
import { signChatMessage } from "./chatSigning";
import crypto from "crypto";

type ConnState = "handshake" | "login" | "configuration" | "play";

const PACKET_IDS = {
  login: {
    clientbound: { Disconnect: 0, EncryptionRequest: 1, LoginSuccess: 2, SetCompression: 3, LoginPluginRequest: 4 },
    serverbound: { LoginStart: 0, EncryptionResponse: 1, LoginPluginResponse: 2, LoginAcknowledged: 3 },
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
    serverbound: { ChatMessage: 9, PlayerSession: 10, KeepAlive: 28 },
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
  /** If provided, chat messages are cryptographically signed (see sendChat). Servers with
   * enforce-secure-profile=true reject unsigned chat otherwise. */
  certificate?: PlayerCertificate;
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
  private chatSessionId = crypto.randomBytes(16); // one random session per connection
  private chatMessageIndex = 0;

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
    // The handshake's "next state" field only tells the *server* to switch — our own state
    // machine has to flip too, or handlePacket()'s switch (which has no "handshake" case) will
    // silently drop every packet we receive from here on. This is the reason connections kept
    // timing out: the server would send an Encryption Request that we genuinely received but
    // just never processed, so it eventually gave up waiting for our response.
    this.state = "login";
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

  private sendLoginPluginResponse(messageId: number) {
    const w = new PacketWriter(PACKET_IDS.login.serverbound.LoginPluginResponse)
      .writeVarInt(messageId)
      .writeBoolean(false); // "successful: false" — we don't understand this channel
    this.sendFrame(w);
  }

  private sendLoginAcknowledged() {
    this.sendFrame(new PacketWriter(PACKET_IDS.login.serverbound.LoginAcknowledged));
    this.state = "configuration";
    this.emit("status", "Entered configuration state");
    // Deliberately not sending Client Information: its field layout (view distance, chat
    // mode, skin parts, etc.) has changed across versions — a real server rejected our attempt
    // with "Failed to decode packet 'serverbound/minecraft:client_information'" — and none of
    // that data matters for a read-only chat client anyway. Servers use sane defaults when it's
    // never sent.
  }

  private sendKnownPacksEmpty() {
    const w = new PacketWriter(PACKET_IDS.configuration.serverbound.KnownPacks).writeVarInt(0);
    this.sendFrame(w);
  }

  private sendFinishConfigurationAck() {
    this.sendFrame(new PacketWriter(PACKET_IDS.configuration.serverbound.AcknowledgeFinishConfiguration));
    this.state = "play";
    this.emit("status", "Entered play state");
    if (this.opts.certificate) {
      this.sendPlayerSession(this.opts.certificate);
    }
  }

  /** Registers our signing key pair with the server so it'll accept signed chat from us. */
  private sendPlayerSession(cert: PlayerCertificate) {
    const w = new PacketWriter(PACKET_IDS.play.serverbound.PlayerSession)
      .writeRaw(this.chatSessionId)
      .writeLong(BigInt(cert.expiresAt))
      .writePrefixedBytes(cert.publicKeyDer)
      .writePrefixedBytes(cert.keySignature);
    this.sendFrame(w);
    this.emit("status", "Sent chat signing session");
  }

  private sendConfigurationPong(payload: Buffer) {
    this.sendFrame(new PacketWriter(PACKET_IDS.configuration.serverbound.Pong).writeRaw(payload));
  }

  private sendPlayKeepAlive(id: bigint) {
    this.sendFrame(new PacketWriter(PACKET_IDS.play.serverbound.KeepAlive).writeLong(id));
  }

  get isPlaying(): boolean {
    return this.state === "play";
  }

  /**
   * Sends a chat message, signed if a certificate was provided (see RawClientOptions),
   * otherwise plain/unsigned. Servers with secure chat enforcement enabled
   * (`enforce-secure-profile=true`) reject unsigned messages with a clean
   * chat.disabled.missingProfileKey response (not a crash or kick).
   */
  sendChat(message: string) {
    if (this.state !== "play") {
      throw new Error("Not connected (not in play state yet)");
    }
    const cert = this.opts.certificate;
    const timestampMs = Date.now();
    const salt = cert ? crypto.randomBytes(8).readBigInt64BE(0) : 0n;
    const index = this.chatMessageIndex++;

    const w = new PacketWriter(PACKET_IDS.play.serverbound.ChatMessage)
      .writeString(message)
      .writeLong(BigInt(timestampMs))
      .writeLong(salt);

    if (cert) {
      const signature = signChatMessage(
        cert.privateKey,
        this.opts.profile.id,
        this.chatSessionId,
        index,
        salt,
        timestampMs,
        message
      );
      w.writeBoolean(true).writeRaw(signature); // fixed 256 bytes, no separate length prefix
    } else {
      w.writeBoolean(false);
    }

    w.writeVarInt(0) // message count acknowledged
      .writeRaw(Buffer.alloc(3)) // fixed 20-bit "acknowledged" bitset, all-zero (nothing tracked)
      .writeRaw(Buffer.from([0])); // checksum over the last-seen set's signatures — 0 since we track none
    this.sendFrame(w);
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
      case "handshake":
        // We should never still be in "handshake" once a packet arrives — sendHandshake()
        // flips state to "login" immediately after sending. If this fires, that transition
        // broke again; surface it loudly instead of silently dropping the packet.
        this.emit("status", `Received packet id=${id} while still in handshake state (bug)`);
        return;
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
    } else if (id === P.LoginPluginRequest) {
      // Sent by some proxies/plugins (e.g. Velocity forwarding, auth plugins) to challenge the
      // client with a custom channel before login can finish. We support no plugin channels, so
      // we must still reply with "unsuccessful" — silently ignoring this packet (the previous
      // bug) left the server waiting for a response that never came, until it gave up and
      // disconnected us for a timeout.
      const messageId = reader.readVarInt();
      const channel = reader.readString();
      this.emit("status", `Login plugin request on unsupported channel "${channel}", declining`);
      this.sendLoginPluginResponse(messageId);
    } else {
      this.emit("status", `Unhandled login packet id=${id}`);
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
   * Field layout confirmed against minecraft.wiki's Player Chat Message section (fetched via
   * the parse API directly, same approach that found the serverbound Chat packet's missing
   * Checksum byte): Global Index, Sender UUID, Index, Message Signature (prefixed optional,
   * fixed 256 bytes), Message, Timestamp, Salt, Previous Messages (prefixed array of {Message
   * ID, [Signature if ID==0]}), Unsigned Content (prefixed optional Text Component), Filter
   * Type (+ Filter Type Bits, a fixed 20-bit BitSet serialized as one Long, if partially
   * filtered), Chat Type (VarInt registry ID into minecraft:chat_type — we don't track that
   * registry, so we render every chat type the same way rather than resolving its exact
   * translation/decoration), Sender Name (Text Component — the player's nickname/title, with
   * any team prefix/suffix folded into its `extra` array), Target Name (prefixed optional Text
   * Component, only set for whispers).
   */
  private handlePlayerChatMessage(reader: PacketReader) {
    try {
      reader.readVarInt(); // global index
      reader.readUUID(); // sender
      reader.readVarInt(); // index
      const hasSignature = reader.readBoolean();
      if (hasSignature) reader.readFixedBytes(256); // fixed-length signature, no length prefix
      const message = reader.readString();
      reader.readLong(); // timestamp
      reader.readLong(); // salt

      const previousCount = reader.readVarInt();
      for (let i = 0; i < previousCount; i++) {
        const messageId = reader.readVarInt(); // "message ID + 1"; 0 means "not a known message"
        if (messageId === 0) reader.readFixedBytes(256);
      }

      let displayText = message;
      const hasUnsignedContent = reader.readBoolean();
      if (hasUnsignedContent) {
        displayText = textComponentToPlainText(reader.readNbt());
      }

      const filterType = reader.readVarInt();
      if (filterType === 2) reader.readFixedBytes(8); // PARTIALLY_FILTERED: 20-bit BitSet as one Long
      reader.readVarInt(); // chat type registry id
      const senderName = textComponentToPlainText(reader.readNbt());
      const hasTargetName = reader.readBoolean();
      const targetName = hasTargetName ? textComponentToPlainText(reader.readNbt()) : null;

      const prefix = targetName ? `${senderName} -> ${targetName}` : senderName;
      this.emit("chat", { text: `<${prefix}> ${displayText}`, raw: "player" } as ChatEvent);
    } catch {
      this.emit("chat", { text: "[플레이어 채팅 메시지 — 파싱 실패]", raw: "player" } as ChatEvent);
    }
  }
}
