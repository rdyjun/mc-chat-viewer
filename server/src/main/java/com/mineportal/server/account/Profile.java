package com.mineportal.server.account;

/** id is the 32-char hex Minecraft profile UUID with no dashes — matches the old Node
 * backend's Profile.id shape (server list ownership keys off this exact string). */
public record Profile(String id, String name) {
}
