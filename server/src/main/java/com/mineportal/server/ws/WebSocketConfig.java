package com.mineportal.server.ws;

import com.mineportal.server.connection.ServerWsHandler;
import com.mineportal.server.desktop.DesktopWsHandler;
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
    private final DesktopWsHandler desktopWsHandler;

    public WebSocketConfig(ServerWsHandler serverWsHandler, DesktopWsHandler desktopWsHandler) {
        this.serverWsHandler = serverWsHandler;
        this.desktopWsHandler = desktopWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(serverWsHandler, "/ws")
                .addInterceptors(new SidHandshakeInterceptor())
                .setAllowedOrigins(ALLOWED_ORIGINS);

        // 데스크톱 앱 전용 채널 — 브라우저 쿠키가 아니라 최초 메시지의 페어링 코드/디바이스
        // 토큰으로 인증하므로(DesktopWsHandler), Origin 기반 방어가 필요 없다. 브라우저가
        // 아닌 순수 자바 WS 클라이언트라 Origin 헤더 자체를 안 보내는 경우가 대부분이기도 하다.
        registry.addHandler(desktopWsHandler, "/app-ws")
                .setAllowedOrigins("*");
    }

}
