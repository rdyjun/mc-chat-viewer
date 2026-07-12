import crypto from "crypto";

/**
 * Minecraft's non-standard "server hash" used for session-join verification: SHA-1 over
 * (serverId + sharedSecret + publicKey), then interpreted as a signed two's-complement BigInt
 * and printed in hex (negative numbers get a leading "-"). This exact quirk is unchanged since
 * the original protocol and is independent of Minecraft version.
 */
export function minecraftServerHash(serverId: string, sharedSecret: Buffer, publicKey: Buffer): string {
  const hash = crypto.createHash("sha1");
  hash.update(serverId, "utf8");
  hash.update(sharedSecret);
  hash.update(publicKey);
  const digest = hash.digest();

  let value = BigInt("0x" + digest.toString("hex"));
  const negative = (digest[0] & 0x80) !== 0;
  if (negative) {
    // Two's complement negation over the 160-bit digest.
    const mask = (1n << 160n) - 1n;
    value = -(((~value) & mask) + 1n);
  }
  return value.toString(16);
}

export function generateSharedSecret(): Buffer {
  return crypto.randomBytes(16);
}

export function rsaEncrypt(publicKeyDer: Buffer, data: Buffer): Buffer {
  const publicKey = crypto.createPublicKey({ key: publicKeyDer, format: "der", type: "spki" });
  return crypto.publicEncrypt({ key: publicKey, padding: crypto.constants.RSA_PKCS1_PADDING }, data);
}

/**
 * Minecraft's Java protocol uses AES/CFB8/NoPadding with the shared secret as both key and IV.
 * Node's built-in cipher/decipher streams handle this directly via `aes-128-cfb8`.
 */
export function makeCipherStreams(sharedSecret: Buffer) {
  const cipher = crypto.createCipheriv("aes-128-cfb8", sharedSecret, sharedSecret);
  const decipher = crypto.createDecipheriv("aes-128-cfb8", sharedSecret, sharedSecret);
  return { cipher, decipher };
}

/** Tells Mojang's session server this account is joining a given server (required before the
 * client sends its Encryption Response), so the server's own session check succeeds. */
export async function joinMojangSession(
  accessToken: string,
  selectedProfileId: string,
  serverHash: string
): Promise<void> {
  const res = await fetch("https://sessionserver.mojang.com/session/minecraft/join", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      accessToken,
      selectedProfile: selectedProfileId,
      serverId: serverHash,
    }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Mojang session join failed: ${res.status} ${body}`);
  }
}
