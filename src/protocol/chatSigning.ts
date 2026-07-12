import crypto from "crypto";

/**
 * Builds the byte sequence a chat message's signature is computed over, and signs it.
 *
 * This mirrors vanilla's SignedMessageBody/MessageSignature format as best understood without
 * an authoritative spec for this exact server version (unlike the packet framing/field-length
 * bugs elsewhere in this codebase, a wrong byte layout here fails "softly": the server can only
 * reject the signature as invalid, not desync its packet decoder, since by the time it's
 * checking cryptographic validity the packet has already been fully and correctly parsed).
 *
 * Layout: version marker (1) + sender UUID (16 bytes) + session UUID (16 bytes) +
 * message index (4) + salt (8) + timestamp in epoch *seconds* (8) + message length (4) +
 * message UTF-8 bytes + last-seen-message count (4, always 0 here since we don't track any).
 */
export function signChatMessage(
  privateKey: crypto.KeyObject,
  senderUuidHex: string, // 32 hex chars, no dashes
  sessionId: Buffer, // 16 raw bytes
  messageIndex: number,
  salt: bigint,
  timestampMs: number,
  message: string
): Buffer {
  const parts: Buffer[] = [];

  const versionMarker = Buffer.alloc(4);
  versionMarker.writeInt32BE(1, 0);
  parts.push(versionMarker);

  parts.push(Buffer.from(senderUuidHex, "hex"));
  parts.push(sessionId);

  const indexBuf = Buffer.alloc(4);
  indexBuf.writeInt32BE(messageIndex, 0);
  parts.push(indexBuf);

  const saltBuf = Buffer.alloc(8);
  saltBuf.writeBigInt64BE(salt, 0);
  parts.push(saltBuf);

  const timestampBuf = Buffer.alloc(8);
  timestampBuf.writeBigInt64BE(BigInt(Math.floor(timestampMs / 1000)), 0);
  parts.push(timestampBuf);

  const messageBytes = Buffer.from(message, "utf8");
  const messageLenBuf = Buffer.alloc(4);
  messageLenBuf.writeInt32BE(messageBytes.length, 0);
  parts.push(messageLenBuf);
  parts.push(messageBytes);

  const lastSeenCountBuf = Buffer.alloc(4); // always 0: we never track previously-seen messages
  parts.push(lastSeenCountBuf);

  const signableContent = Buffer.concat(parts);
  return crypto.sign("RSA-SHA256", signableContent, privateKey);
}
