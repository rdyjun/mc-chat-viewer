package com.mineportal.server.connection;

public class NotConnectedException extends RuntimeException {
    public NotConnectedException() {
        super("This server isn't connected yet");
    }
}
