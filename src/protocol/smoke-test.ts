import assert from "assert";
import { readVarInt, writeVarInt } from "./varint";
import { PacketReader, PacketWriter } from "./packetIO";
import { minecraftServerHash } from "./crypto";
import { readNetworkNbt } from "./nbt";
import { textComponentToPlainText } from "./textComponent";

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

console.log("\nAll protocol smoke checks passed.");
