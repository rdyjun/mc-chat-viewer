import crypto from "crypto";

export interface PlayerCertificate {
  privateKey: crypto.KeyObject;
  /** X.509 SubjectPublicKeyInfo DER bytes — the exact encoding the Player Session packet wants. */
  publicKeyDer: Buffer;
  /** Mojang's signature over (player UUID + expiresAt + publicKeyDer), proving this key pair is legit. */
  keySignature: Buffer;
  expiresAt: number; // epoch ms
}

/**
 * Fetches a fresh RSA key pair from Mojang for signing chat messages, per
 * https://minecraft.wiki/w/Mojang_API#Player_Certificates. Valid ~48h; callers should re-fetch
 * once `expiresAt` passes rather than caching long-term.
 */
export async function fetchPlayerCertificate(accessToken: string): Promise<PlayerCertificate> {
  const res = await fetch("https://api.minecraftservices.com/player/certificates", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Failed to fetch player certificate: ${res.status} ${body}`);
  }
  const data = (await res.json()) as {
    keyPair: { privateKey: string; publicKey: string };
    publicKeySignatureV2: string;
    expiresAt: string;
  };

  // The private key PEM is genuinely PKCS#1 ("RSA PRIVATE KEY") and needs that type spelled out
  // for Node to parse it (header-only auto-detection isn't reliable).
  const privateKey = crypto.createPrivateKey({ key: data.keyPair.privateKey, format: "pem", type: "pkcs1" });

  // The public key PEM is labeled "RSA PUBLIC KEY" (implying PKCS#1) but its DER content is
  // actually already X.509 SubjectPublicKeyInfo — confirmed by decoding it, its base64 body
  // starts with the nested AlgorithmIdentifier/OID sequence that raw PKCS#1 (just modulus +
  // exponent) wouldn't have. That mismatched label makes Node's own PEM parser reject it no
  // matter what `type` is requested (it cross-checks the header against the type, and "RSA
  // PUBLIC KEY" never satisfies "spki"). We don't need a KeyObject for the public key anyway —
  // the Player Session packet just wants the raw SPKI DER bytes — so sidestep the header check
  // entirely by decoding the PEM body ourselves.
  const publicKeyDer = pemBodyToDer(data.keyPair.publicKey);

  return {
    privateKey,
    publicKeyDer,
    keySignature: Buffer.from(data.publicKeySignatureV2, "base64"),
    expiresAt: Date.parse(data.expiresAt),
  };
}

function pemBodyToDer(pem: string): Buffer {
  const base64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  return Buffer.from(base64, "base64");
}
