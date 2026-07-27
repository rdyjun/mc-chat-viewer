package com.mineportal.server.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ServerWsHandler serverWsHandler;

    public WebSocketConfig(ServerWsHandler serverWsHandler) {
        this.serverWsHandler = serverWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(serverWsHandler, "/ws")
                .addInterceptors(new SidHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

}
