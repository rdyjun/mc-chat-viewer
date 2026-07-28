package com.mineportal.server.connection;

public record ChatMessage(String username, String message, long timestamp) {
}
