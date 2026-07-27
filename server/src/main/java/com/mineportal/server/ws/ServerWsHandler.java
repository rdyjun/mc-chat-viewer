package com.mineportal.server.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ServerWsHandler extends TextWebSocketHandler {

    private final EventBroadcaster broadcaster;

    public ServerWsHandler(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.register(session);
        broadcaster.sendAccountSnapshot(session);
        broadcaster.sendServersSnapshot(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }

}
