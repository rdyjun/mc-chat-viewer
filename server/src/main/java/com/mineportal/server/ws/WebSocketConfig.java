package com.mineportal.server.ws;

import com.mineportal.server.connection.ServerWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    // "*" would let any site open a WS connection and ride the visitor's "sid" cookie in the
    // handshake (cross-site WebSocket hijacking) — WS isn't covered by the browser's CORS
    // same-origin checks the way fetch()/XHR are, so this allowlist is the only thing enforcing
    // it. Defaults to local dev's own origin; prod sets WS_ALLOWED_ORIGINS to the real domain(s).
    private static final String[] ALLOWED_ORIGINS = System.getenv()
            .getOrDefault("WS_ALLOWED_ORIGINS", "http://localhost:3000")
            .split(",");

    private final ServerWsHandler serverWsHandler;

    public WebSocketConfig(ServerWsHandler serverWsHandler) {
        this.serverWsHandler = serverWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(serverWsHandler, "/ws")
                .addInterceptors(new SidHandshakeInterceptor())
                .setAllowedOrigins(ALLOWED_ORIGINS);
    }

}
