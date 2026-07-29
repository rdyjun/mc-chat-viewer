package com.mineportal.server.ws;

import com.mineportal.server.connection.ServerWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    // "*"로 두면 어떤 사이트든 WS 연결을 열어 핸드셰이크에 방문자의 "sid" 쿠키를 실어
    // 보낼 수 있게 된다(크로스 사이트 WebSocket 하이재킹) — WS는 fetch()/XHR과 달리
    // 브라우저의 CORS 동일 출처 검사 대상이 아니므로, 이 허용 목록이 유일한 방어선이다.
    // 기본값은 로컬 개발 환경 자신의 출처이며, 운영 환경은 WS_ALLOWED_ORIGINS에 실제
    // 도메인(들)을 설정한다.
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
