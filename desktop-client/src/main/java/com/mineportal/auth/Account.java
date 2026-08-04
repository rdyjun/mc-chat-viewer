package com.mineportal.auth;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.UUID;

/** An authenticated Minecraft Java account: what MCProtocolLib needs to log in, plus the
 * (optional) chat-signing certificate the backend forwards when it's issued and unexpired.
 * Without a certificate, chat/commands are sent unsigned and servers running
 * enforce-secure-profile=true will silently drop them. */
public final class Account {

    public final UUID uuid;
    public final String name;
    public final String accessToken;
    public final SigningCert signingCert;

    public Account(UUID uuid, String name, String accessToken, SigningCert signingCert) {
        this.uuid = uuid;
        this.name = name;
        this.accessToken = accessToken;
        this.signingCert = signingCert;
    }

    public record SigningCert(PrivateKey privateKey, PublicKey publicKey, byte[] publicKeySignature, long expiresAtMs) {
        public boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMs;
        }
    }
}
