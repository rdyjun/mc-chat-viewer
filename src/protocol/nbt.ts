/**
 * Minimal NBT reader — only what's needed to decode Text Components sent over the network
 * (which use the "unnamed root" network NBT variant: the root tag has no name prefix, unlike
 * file-format NBT). Produces plain JS values: string | number | boolean | NbtValue[] |
 * { [key: string]: NbtValue }.
 */

export type NbtValue = string | number | boolean | NbtValue[] | { [key: string]: NbtValue };

const enum TagType {
  End = 0,
  Byte = 1,
  Short = 2,
  Int = 3,
  Long = 4,
  Float = 5,
  Double = 6,
  ByteArray = 7,
  String = 8,
  List = 9,
  Compound = 10,
  IntArray = 11,
  LongArray = 12,
}

class Cursor {
  offset = 0;
  constructor(public buf: Buffer) {}
}

function readString(c: Cursor): string {
  const len = c.buf.readUInt16BE(c.offset);
  c.offset += 2;
  const str = c.buf.toString("utf8", c.offset, c.offset + len);
  c.offset += len;
  return str;
}

function readPayload(c: Cursor, type: TagType): NbtValue {
  switch (type) {
    case TagType.Byte: {
      const v = c.buf.readInt8(c.offset);
      c.offset += 1;
      return v;
    }
    case TagType.Short: {
      const v = c.buf.readInt16BE(c.offset);
      c.offset += 2;
      return v;
    }
    case TagType.Int: {
      const v = c.buf.readInt32BE(c.offset);
      c.offset += 4;
      return v;
    }
    case TagType.Long: {
      const v = c.buf.readBigInt64BE(c.offset);
      c.offset += 8;
      return Number(v);
    }
    case TagType.Float: {
      const v = c.buf.readFloatBE(c.offset);
      c.offset += 4;
      return v;
    }
    case TagType.Double: {
      const v = c.buf.readDoubleBE(c.offset);
      c.offset += 8;
      return v;
    }
    case TagType.ByteArray: {
      const len = c.buf.readInt32BE(c.offset);
      c.offset += 4;
      const arr: number[] = [];
      for (let i = 0; i < len; i++) arr.push(readPayload(c, TagType.Byte) as number);
      return arr;
    }
    case TagType.String:
      return readString(c);
    case TagType.List: {
      const elementType = c.buf.readUInt8(c.offset);
      c.offset += 1;
      const len = c.buf.readInt32BE(c.offset);
      c.offset += 4;
      const arr: NbtValue[] = [];
      for (let i = 0; i < len; i++) arr.push(readPayload(c, elementType));
      return arr;
    }
    case TagType.Compound: {
      const obj: { [key: string]: NbtValue } = {};
      for (;;) {
        const childType = c.buf.readUInt8(c.offset);
        c.offset += 1;
        if (childType === TagType.End) break;
        const name = readString(c);
        obj[name] = readPayload(c, childType);
      }
      return obj;
    }
    case TagType.IntArray: {
      const len = c.buf.readInt32BE(c.offset);
      c.offset += 4;
      const arr: number[] = [];
      for (let i = 0; i < len; i++) arr.push(readPayload(c, TagType.Int) as number);
      return arr;
    }
    case TagType.LongArray: {
      const len = c.buf.readInt32BE(c.offset);
      c.offset += 4;
      const arr: number[] = [];
      for (let i = 0; i < len; i++) arr.push(readPayload(c, TagType.Long) as number);
      return arr;
    }
    default:
      throw new Error(`Unknown NBT tag type: ${type}`);
  }
}

/** Reads a network-style (unnamed root) NBT tag starting at `offset`. Returns value + bytes consumed. */
export function readNetworkNbt(buf: Buffer, offset: number): { value: NbtValue; length: number } {
  const c = new Cursor(buf.subarray(offset));
  const type = c.buf.readUInt8(c.offset);
  c.offset += 1;
  if (type === TagType.End) return { value: "", length: c.offset };
  const value = readPayload(c, type);
  return { value, length: c.offset };
}
