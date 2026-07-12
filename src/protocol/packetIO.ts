import { readVarInt, writeVarInt } from "./varint";
import { readNetworkNbt, NbtValue } from "./nbt";

/** Builds an outgoing packet body (packet ID + fields), before length-prefix framing. */
export class PacketWriter {
  private chunks: Buffer[] = [];

  constructor(packetId: number) {
    this.writeVarInt(packetId);
  }

  writeVarInt(value: number): this {
    this.chunks.push(writeVarInt(value));
    return this;
  }

  writeString(value: string): this {
    const buf = Buffer.from(value, "utf8");
    this.writeVarInt(buf.length);
    this.chunks.push(buf);
    return this;
  }

  writeUnsignedShort(value: number): this {
    const buf = Buffer.alloc(2);
    buf.writeUInt16BE(value, 0);
    this.chunks.push(buf);
    return this;
  }

  writeBoolean(value: boolean): this {
    this.chunks.push(Buffer.from([value ? 1 : 0]));
    return this;
  }

  writeLong(value: bigint): this {
    const buf = Buffer.alloc(8);
    buf.writeBigInt64BE(value, 0);
    this.chunks.push(buf);
    return this;
  }

  writeUUID(uuidNoDashes: string): this {
    this.chunks.push(Buffer.from(uuidNoDashes, "hex"));
    return this;
  }

  /** Length-prefixed raw byte array. */
  writePrefixedBytes(data: Buffer): this {
    this.writeVarInt(data.length);
    this.chunks.push(data);
    return this;
  }

  writeRaw(data: Buffer): this {
    this.chunks.push(data);
    return this;
  }

  build(): Buffer {
    return Buffer.concat(this.chunks);
  }
}

/** Sequentially reads fields out of a decoded packet body (packet ID already stripped). */
export class PacketReader {
  private offset = 0;
  constructor(private buf: Buffer) {}

  get remaining(): number {
    return this.buf.length - this.offset;
  }

  readVarInt(): number {
    const { value, length } = readVarInt(this.buf, this.offset);
    this.offset += length;
    return value;
  }

  readString(): string {
    const len = this.readVarInt();
    const str = this.buf.toString("utf8", this.offset, this.offset + len);
    this.offset += len;
    return str;
  }

  readUnsignedShort(): number {
    const v = this.buf.readUInt16BE(this.offset);
    this.offset += 2;
    return v;
  }

  readBoolean(): boolean {
    const v = this.buf.readUInt8(this.offset) !== 0;
    this.offset += 1;
    return v;
  }

  readLong(): bigint {
    const v = this.buf.readBigInt64BE(this.offset);
    this.offset += 8;
    return v;
  }

  readUUID(): string {
    const bytes = this.buf.subarray(this.offset, this.offset + 16);
    this.offset += 16;
    return bytes.toString("hex");
  }

  readPrefixedBytes(): Buffer {
    const len = this.readVarInt();
    const bytes = this.buf.subarray(this.offset, this.offset + len);
    this.offset += len;
    return bytes;
  }

  readFixedBytes(len: number): Buffer {
    const bytes = this.buf.subarray(this.offset, this.offset + len);
    this.offset += len;
    return bytes;
  }

  /** Everything left in the packet, unparsed. */
  readRest(): Buffer {
    const bytes = this.buf.subarray(this.offset);
    this.offset = this.buf.length;
    return bytes;
  }

  /** Reads a network-style (unnamed root) NBT tag, e.g. a Text Component. */
  readNbt(): NbtValue {
    const { value, length } = readNetworkNbt(this.buf, this.offset);
    this.offset += length;
    return value;
  }
}
