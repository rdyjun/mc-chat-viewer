package com.mineportal.server.servers;

public record ChatMessage(String username, String message, long timestamp) {
}
