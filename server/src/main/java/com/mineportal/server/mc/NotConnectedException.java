package com.mineportal.server.mc;

public class NotConnectedException extends RuntimeException {
    public NotConnectedException() {
        super("This server isn't connected yet");
    }
}
