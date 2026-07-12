/** VarInt/VarLong encoding as used for Minecraft's packet framing and most integer fields. */

export function writeVarInt(value: number): Buffer {
  const bytes: number[] = [];
  let v = value | 0;
  do {
    let temp = v & 0b01111111;
    v >>>= 7;
    if (v !== 0) temp |= 0b10000000;
    bytes.push(temp);
  } while (v !== 0);
  return Buffer.from(bytes);
}

/** Reads a VarInt starting at `offset`. Returns the value and the number of bytes consumed. */
export function readVarInt(buf: Buffer, offset: number): { value: number; length: number } {
  let numRead = 0;
  let result = 0;
  let read: number;
  do {
    if (offset + numRead >= buf.length) {
      throw new RangeError("buffer too short for varint");
    }
    read = buf.readUInt8(offset + numRead);
    result |= (read & 0b01111111) << (7 * numRead);
    numRead++;
    if (numRead > 5) throw new RangeError("varint too long");
  } while ((read & 0b10000000) !== 0);
  return { value: result, length: numRead };
}

export function writeVarLong(value: bigint): Buffer {
  const bytes: number[] = [];
  let v = BigInt.asUintN(64, value);
  do {
    let temp = Number(v & 0x7fn);
    v >>= 7n;
    if (v !== 0n) temp |= 0x80;
    bytes.push(temp);
  } while (v !== 0n);
  return Buffer.from(bytes);
}
