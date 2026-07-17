package com.mineportal.auth;

import java.util.UUID;

/** An authenticated Minecraft Java account: what MCProtocolLib needs to log in. */
public final class Account {

    public final UUID uuid;
    public final String name;
    public final String accessToken;

    public Account(UUID uuid, String name, String accessToken) {
        this.uuid = uuid;
        this.name = name;
        this.accessToken = accessToken;
    }
}
