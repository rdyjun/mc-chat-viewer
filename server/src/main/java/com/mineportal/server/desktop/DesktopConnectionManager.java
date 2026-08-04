package com.mineportal.server.desktop;

import com.mineportal.server.account.AccountState;
import net.raphimc.minecraftauth.step.java.StepMCProfile;
import net.raphimc.minecraftauth.step.java.StepMCToken;
import net.raphimc.minecraftauth.step.java.StepPlayerCertificates;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 페어링된 데스크톱 앱의 아웃바운드 WS 커넥션을 계정(ownerId) 단위로 추적한다. 실제
 * 마인크래프트 접속은 이 앱이 사용자 PC에서 직접 수행하고, 여기서는 그 커넥션으로
 * 명령(connect/disconnect/send-chat/token)을 실어보내고, 앱이 올려보내는 이벤트를
 * 받을 대상만 찾아준다 — 게임 트래픽 자체는 이 서버를 거치지 않는다.
 */
@Component
public class DesktopConnectionManager {

    private static final long PAIR_CODE_TTL_MS = 5 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 헷갈리는 0/O/1/I 제외

    private record PendingCode(String ownerId, long expiresAt) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, PendingCode> pendingCodes = new ConcurrentHashMap<>();
    private final Map<String, String> deviceTokens = new ConcurrentHashMap<>(); // deviceToken -> ownerId
    private final Map<String, WebSocketSession> appSessions = new ConcurrentHashMap<>(); // ownerId -> session
    private final Set<String> appRoutedServers = ConcurrentHashMap.newKeySet(); // serverId currently handled by an app

    /** 브라우저가 로그인된 상태에서 "데스크톱 앱 연동" 버튼을 누르면 발급되는 1회용 코드. */
    public String createPairingCode(String ownerId) {
        String code = randomCode();
        pendingCodes.put(code, new PendingCode(ownerId, System.currentTimeMillis() + PAIR_CODE_TTL_MS));
        return code;
    }

    /** 코드를 소비하고 연결된 ownerId를 반환한다 — 만료/미존재면 null (1회용이라 성공/실패 상관없이 제거됨). */
    public String consumePairingCode(String code) {
        PendingCode pending = pendingCodes.remove(code);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) return null;
        return pending.ownerId();
    }

    public String issueDeviceToken(String ownerId) {
        String token = randomToken();
        deviceTokens.put(token, ownerId);
        return token;
    }

    public String ownerForDeviceToken(String token) {
        return token == null ? null : deviceTokens.get(token);
    }

    public void register(String ownerId, WebSocketSession session) {
        WebSocketSession previous = appSessions.put(ownerId, session);
        if (previous != null && previous != session && previous.isOpen()) {
            try {
                previous.close();
            } catch (Exception ignored) {
            }
        }
        session.getAttributes().put("ownerId", ownerId);
    }

    public void unregister(WebSocketSession session) {
        String ownerId = (String) session.getAttributes().get("ownerId");
        if (ownerId != null) {
            appSessions.remove(ownerId, session);
        }
    }

    public String ownerIdOf(WebSocketSession session) {
        return (String) session.getAttributes().get("ownerId");
    }

    public boolean isConnected(String ownerId) {
        WebSocketSession session = appSessions.get(ownerId);
        return session != null && session.isOpen();
    }

    public void markRouted(String serverId) {
        appRoutedServers.add(serverId);
    }

    public void unmarkRouted(String serverId) {
        appRoutedServers.remove(serverId);
    }

    public boolean isRouted(String serverId) {
        return appRoutedServers.contains(serverId);
    }

    /** 이 계정으로 앱이 붙어있으면 마인크래프트 액세스 토큰을 그 자리에서 밀어준다 — 로그인
     * 완료 직후, 그리고 앱이 로그인 완료 이후에 새로 페어링/재접속했을 때 둘 다 호출된다.
     * 브라우저는 이 토큰을 절대 보지 않는다(AccountState.fullSession의 서버 전용 필드).
     * 서명 인증서(있고 만료 전이면)도 함께 보내서, 앱이 McConnectionManager와 동일하게
     * enforce-secure-profile 서버용 채팅/명령어 서명을 직접 할 수 있게 한다. */
    public void pushTokenIfConnected(AccountState state) {
        if (state.profile == null || state.fullSession == null) return;
        String ownerId = state.profile.id();
        if (!isConnected(ownerId)) return;
        StepMCProfile.MCProfile mcProfile = state.fullSession.getMcProfile();
        StepMCToken.MCToken token = mcProfile.getMcToken();

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "token");
        payload.put("uuid", mcProfile.getId().toString());
        payload.put("name", mcProfile.getName());
        payload.put("accessToken", token.getAccessToken());

        StepPlayerCertificates.PlayerCertificates certs = state.fullSession.getPlayerCertificates();
        if (certs != null && !certs.isExpired()) {
            payload.put("certExpiresAt", certs.getExpireTimeMs());
            payload.put("certPrivateKey", Base64.getEncoder().encodeToString(certs.getPrivateKey().getEncoded()));
            payload.put("certPublicKey", Base64.getEncoder().encodeToString(certs.getPublicKey().getEncoded()));
            payload.put("certPublicKeySignature", Base64.getEncoder().encodeToString(certs.getPublicKeySignature()));
        }
        send(ownerId, payload);
    }

    public void send(String ownerId, Map<String, Object> payload) {
        WebSocketSession session = appSessions.get(ownerId);
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            System.err.println("Failed to send desktop-app WS message: " + e.getMessage());
        }
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

}
