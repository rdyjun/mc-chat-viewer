import assert from "assert";
import crypto from "crypto";
import { readVarInt, writeVarInt } from "./varint";
import { PacketReader, PacketWriter } from "./packetIO";
import { minecraftServerHash } from "./crypto";
import { readNetworkNbt } from "./nbt";
import { textComponentToPlainText } from "./textComponent";
import { signChatMessage } from "./chatSigning";

// VarInt roundtrip across representative values (0, small, boundary at 1-byte/2-byte cutoff, large).
for (const v of [0, 1, 127, 128, 255, 300, 25565, 2097151, 2147483647]) {
  const encoded = writeVarInt(v);
  const { value, length } = readVarInt(encoded, 0);
  assert.strictEqual(value, v, `varint roundtrip failed for ${v}`);
  assert.strictEqual(length, encoded.length);
}
console.log("VarInt roundtrip: OK");

// Known VarInt encodings per the Minecraft wiki examples.
assert.deepStrictEqual([...writeVarInt(25565)], [0xdd, 0xc7, 0x01]);
assert.deepStrictEqual([...writeVarInt(2)], [0x02]);
assert.deepStrictEqual([...writeVarInt(127)], [0x7f]);
console.log("VarInt known-value encoding: OK");

// PacketWriter/Reader roundtrip for the field types Handshake/Login actually use.
const w = new PacketWriter(0x00)
  .writeVarInt(775)
  .writeString("play.example.net")
  .writeUnsignedShort(25565)
  .writeVarInt(2)
  .writeBoolean(true)
  .writeLong(1234567890123n)
  .writeUUID("0102030405060708090a0b0c0d0e0f10");
const built = w.build();
const idInfo = readVarInt(built, 0);
assert.strictEqual(idInfo.value, 0);
const r = new PacketReader(built.subarray(idInfo.length));
assert.strictEqual(r.readVarInt(), 775);
assert.strictEqual(r.readString(), "play.example.net");
assert.strictEqual(r.readUnsignedShort(), 25565);
assert.strictEqual(r.readVarInt(), 2);
assert.strictEqual(r.readBoolean(), true);
assert.strictEqual(r.readLong(), 1234567890123n);
assert.strictEqual(r.readUUID(), "0102030405060708090a0b0c0d0e0f10");
assert.strictEqual(r.remaining, 0);
console.log("PacketWriter/Reader roundtrip: OK");

// Serverbound Chat Message field layout, matching RawMcClient.sendChat().
{
  const now = BigInt(Date.now());
  const chatW = new PacketWriter(9)
    .writeString("hello world")
    .writeLong(now)
    .writeLong(0n)
    .writeBoolean(false)
    .writeVarInt(0)
    .writeRaw(Buffer.alloc(3))
    .writeRaw(Buffer.from([0])); // trailing checksum byte (easy to miss — a real server rejected
  // this packet without it: "Failed to decode packet 'serverbound/minecraft:chat'")
  const chatBuilt = chatW.build();
  const chatIdInfo = readVarInt(chatBuilt, 0);
  assert.strictEqual(chatIdInfo.value, 9);
  const cr = new PacketReader(chatBuilt.subarray(chatIdInfo.length));
  assert.strictEqual(cr.readString(), "hello world");
  assert.strictEqual(cr.readLong(), now);
  assert.strictEqual(cr.readLong(), 0n);
  assert.strictEqual(cr.readBoolean(), false);
  assert.strictEqual(cr.readVarInt(), 0);
  assert.deepStrictEqual([...cr.readFixedBytes(3)], [0, 0, 0]);
  assert.deepStrictEqual([...cr.readFixedBytes(1)], [0]);
  assert.strictEqual(cr.remaining, 0);
}
console.log("Serverbound Chat Message field roundtrip: OK");

// Minecraft server-hash: the well-known wiki.vg test vectors.
assert.strictEqual(
  minecraftServerHash("Notch", Buffer.alloc(0), Buffer.alloc(0)),
  "4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48"
);
assert.strictEqual(
  minecraftServerHash("jeb_", Buffer.alloc(0), Buffer.alloc(0)),
  "-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1"
);
assert.strictEqual(
  minecraftServerHash("simon", Buffer.alloc(0), Buffer.alloc(0)),
  "88e16a1019277b15d58faf0541e11910eb756f6"
);
console.log("Minecraft server hash (wiki.vg test vectors): OK");

// NBT: a simple string root (network/unnamed) — TAG_String(8) + len(2) + utf8 bytes.
{
  const payload = Buffer.from("hi", "utf8");
  const buf = Buffer.concat([Buffer.from([8, 0, payload.length]), payload]);
  const { value, length } = readNetworkNbt(buf, 0);
  assert.strictEqual(value, "hi");
  assert.strictEqual(length, buf.length);
}
console.log("NBT simple string: OK");

// NBT: a compound { text: "Hello", extra: [ "World" ] } and its flattened text.
{
  function nbtString(s: string) {
    const b = Buffer.from(s, "utf8");
    const len = Buffer.alloc(2);
    len.writeUInt16BE(b.length, 0);
    return Buffer.concat([len, b]);
  }
  const nameText = nbtString("text");
  const valueText = nbtString("Hello");
  const nameExtra = nbtString("extra");

  // extra: TAG_List of TAG_String, 1 element "World"
  const worldStr = nbtString("World");
  const listPayload = Buffer.concat([
    Buffer.from([8]), // element type: string
    Buffer.from([0, 0, 0, 1]), // length: 1
    worldStr,
  ]);

  const compoundPayload = Buffer.concat([
    Buffer.from([8]), // TAG_String
    nameText,
    valueText,
    Buffer.from([9]), // TAG_List
    nameExtra,
    listPayload,
    Buffer.from([0]), // TAG_End
  ]);
  const buf = Buffer.concat([Buffer.from([10]), compoundPayload]); // root type: TAG_Compound
  const { value } = readNetworkNbt(buf, 0);
  assert.deepStrictEqual(value, { text: "Hello", extra: ["World"] });
  assert.strictEqual(textComponentToPlainText(value), "HelloWorld");
}
console.log("NBT compound + text component flattening: OK");

// Known translatable components (join/leave/chat-line) render as readable English, unknown
// ones fall back to showing the raw key so nothing silently disappears.
assert.strictEqual(
  textComponentToPlainText({ translate: "multiplayer.player.joined", with: ["geenee10"] }),
  "geenee10 joined the game"
);
assert.strictEqual(
  textComponentToPlainText({ translate: "chat.type.text", with: ["Alice", "hi there"] }),
  "<Alice> hi there"
);
assert.strictEqual(
  textComponentToPlainText({ translate: "some.unknown.key", with: ["x"] }),
  "[some.unknown.key x]"
);
console.log("Known translation-key rendering: OK");

// Chat message signing: self-consistency only — this can't verify the signable-content byte
// layout matches what the real server expects (see chatSigning.ts's doc comment), just that
// signChatMessage() produces a correctly-shaped, genuinely-valid RSA-2048 signature (256 bytes,
// verifiable against the matching public key) rather than garbage or a wrong-length buffer.
{
  const { privateKey, publicKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const senderUuidHex = "0102030405060708090a0b0c0d0e0f10";
  const sessionId = Buffer.alloc(16, 7);
  const timestampMs = Date.now();
  const signature = signChatMessage(privateKey, senderUuidHex, sessionId, 0, 123n, timestampMs, "hello");
  assert.strictEqual(signature.length, 256, "RSA-2048 signature must be exactly 256 bytes");

  // Rebuild the exact same signable content independently and verify with crypto.verify,
  // proving signChatMessage's output is a real signature over its documented byte layout.
  const parts: Buffer[] = [];
  const v = Buffer.alloc(4);
  v.writeInt32BE(1, 0);
  parts.push(v, Buffer.from(senderUuidHex, "hex"), sessionId);
  const idx = Buffer.alloc(4);
  parts.push(idx);
  const salt = Buffer.alloc(8);
  salt.writeBigInt64BE(123n, 0);
  parts.push(salt);
  const ts = Buffer.alloc(8);
  ts.writeBigInt64BE(BigInt(Math.floor(timestampMs / 1000)), 0);
  parts.push(ts);
  const msg = Buffer.from("hello", "utf8");
  const msgLen = Buffer.alloc(4);
  msgLen.writeInt32BE(msg.length, 0);
  parts.push(msgLen, msg, Buffer.alloc(4));
  const signableContent = Buffer.concat(parts);
  assert.ok(
    crypto.verify("RSA-SHA256", signableContent, publicKey, signature),
    "signature must verify against the reconstructed signable content"
  );
}
console.log("Chat message signing (self-consistency): OK");

console.log("\nAll protocol smoke checks passed.");
