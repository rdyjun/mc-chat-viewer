package com.mineportal.server.account;

import com.mineportal.server.session.SidCookieFilter;
import com.mineportal.server.ws.EventBroadcaster;
import jakarta.servlet.http.HttpServletResponse;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.step.AbstractStep;
import net.raphimc.minecraftauth.step.java.session.StepFullJavaSession;
import net.raphimc.minecraftauth.util.MicrosoftConstants;
import net.raphimc.minecraftauth.util.OAuthEnvironment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
public class AuthController {

    private static final String MS_CLIENT_ID = System.getenv().getOrDefault("MS_CLIENT_ID", "");
    private static final String MS_CLIENT_SECRET = System.getenv().getOrDefault("MS_CLIENT_SECRET", "");
    private static final String MS_REDIRECT_URI = System.getenv().getOrDefault("MS_REDIRECT_URI", "");

    // 기존 Azure 앱 등록에 이미 설정되어 있는 것과 동일한 테넌트/엔드포인트를 사용한다
    // (예전 Node 백엔드의 msal-node `authority: ".../consumers"` 설정과 일치).
    private static final AbstractStep.ApplicationDetails APPLICATION_DETAILS = new AbstractStep.ApplicationDetails(
            MS_CLIENT_ID, MicrosoftConstants.SCOPE2, MS_CLIENT_SECRET, MS_REDIRECT_URI, OAuthEnvironment.MICROSOFT_ONLINE_CONSUMERS
    );

    private final HttpClient httpClient = MinecraftAuth.createHttpClient();
    private final StepFullJavaSession fullJavaSessionStep = MinecraftAuth.builder()
            .withApplicationDetails(APPLICATION_DETAILS)
            .customMsaCodeStep(appDetails -> new OAuthCodeMsaCodeStep(appDetails))
            .withoutDeviceToken()
            .regularAuthentication(MicrosoftConstants.JAVA_XSTS_RELYING_PARTY)
            .buildMinecraftJavaProfileStep(true);

    // OAuth CSRF nonce -> 이를 시작한 sid로 매핑해서, 콜백이 어느 세션의 로그인 요청인지
    // 알 수 있게 한다 (예전 Node 백엔드는 전역 계정이 하나뿐이라 단순 Set만 있으면 됐지만,
    // 지금은 세션마다 각자의 AccountState를 가진다).
    private final Map<String, String> pendingLoginStates = new ConcurrentHashMap<>();

    private final AccountSessionManager accountSessions;
    private final EventBroadcaster broadcaster;

    public AuthController(AccountSessionManager accountSessions, EventBroadcaster broadcaster) {
        this.accountSessions = accountSessions;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/api/account")
    public Map<String, Object> account(@RequestAttribute("sid") String sid) {
        return AccountView.of(accountSessions.get(sid));
    }

    @GetMapping("/api/account/login/microsoft")
    public ResponseEntity<?> loginRedirect(@RequestAttribute("sid") String sid) {
        if (MS_CLIENT_ID.isEmpty() || MS_CLIENT_SECRET.isEmpty() || MS_REDIRECT_URI.isEmpty()) {
            return ResponseEntity.status(500).body("Microsoft login isn't configured: MS_CLIENT_ID, MS_CLIENT_SECRET, and MS_REDIRECT_URI must be set");
        }
        String state = UUID.randomUUID().toString();
        pendingLoginStates.put(state, sid);

        Map<String, String> params = new java.util.HashMap<>(APPLICATION_DETAILS.getOAuthParameters());
        params.put("state", state);
        params.put("prompt", "select_account");
        String query = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));

        return ResponseEntity.status(302)
                .location(URI.create(APPLICATION_DETAILS.getOAuthEnvironment().getAuthorizeUrl() + "?" + query))
                .build();
    }

    @GetMapping("/api/account/callback")
    public ResponseEntity<?> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription,
            HttpServletResponse response
    ) {
        if (error != null) {
            return redirectTo("/?loginError=" + encode(errorDescription != null ? errorDescription : error));
        }
        String pendingSid = state != null ? pendingLoginStates.remove(state) : null;
        if (pendingSid == null || code == null) {
            return ResponseEntity.status(400).body("Invalid or expired login attempt. Please try signing in again.");
        }

        // 로그인 시도가 실제로 확인되는 시점에 새로 생성한 sid로 교체한다. 이렇게 하면
        // 공격자가 미리 피해자 브라우저에 심어둔 sid가 인증된 세션까지 이어지지 못한다
        // (세션 고정 공격 방지).
        String sid = UUID.randomUUID().toString();
        accountSessions.remove(pendingSid);
        SidCookieFilter.issueCookie(response, sid);

        completeLoginAsync(sid, code);
        return redirectTo("/");
    }

    // 실제 Microsoft/Xbox Live/XSTS/Minecraft 교환은 별도 스레드에서 실행해서 HTTP 응답이
    // 즉시 반환되게 한다 — 프론트엔드는 "account" WebSocket 이벤트로 진행 상황을 받는다,
    // 예전 Node 백엔드의 fire-and-forget completeMicrosoftLogin()과 동일한 방식.
    private void completeLoginAsync(String sid, String code) {
        AccountState state = accountSessions.get(sid);
        if ("logging-in".equals(state.status)) return;
        state.status = "logging-in";
        state.error = null;
        broadcaster.refreshAccountFor(sid);

        new Thread(() -> {
            try {
                StepFullJavaSession.FullJavaSession session = fullJavaSessionStep.getFromInput(
                        MinecraftAuth.LOGGER, httpClient, new OAuthCodeMsaCodeStep.CodeInput(code));
                state.fullSession = session;
                state.profile = new Profile(
                        session.getMcProfile().getId().toString().replace("-", ""),
                        session.getMcProfile().getName()
                );
                state.status = "logged-in";
                broadcaster.refreshServersFor(sid);
            } catch (Exception e) {
                state.status = "error";
                state.error = translateAuthError(e.getMessage());
            }
            broadcaster.refreshAccountFor(sid);
        }, "mineportal-login-" + sid).start();
    }

    /**
     * MinecraftAuth/MSAL 계열 오류는 영어 원문 예외 메시지 그대로 올라온다. 실제로 자주
     * 마주치는 것들을 알기 쉬운 한국어 설명으로 변환한다. 예전 Node 백엔드의
     * account.ts translateAuthError()를 포팅한 것.
     */
    private String translateAuthError(String message) {
        String m = message == null ? "" : message;
        if (m.matches("(?i).*invalid_grant.*") || m.contains("AADSTS70008")) {
            return "로그인 세션이 만료됐거나 이미 사용된 인증 코드예요. 다시 로그인해주세요.";
        }
        if (m.matches("(?i).*invalid_client.*") || m.contains("AADSTS7000215") || m.contains("AADSTS700016")) {
            return "서버의 Microsoft 로그인 설정(클라이언트 ID/시크릿)이 올바르지 않아요. 관리자에게 문의해주세요.";
        }
        if (m.matches("(?i).*(consent_required|interaction_required|AADSTS65001).*")) {
            return "Microsoft 계정 동의가 필요해요. 로그인을 다시 진행해주세요.";
        }
        if (m.matches("(?i).*(ECONNREFUSED|ENOTFOUND|ETIMEDOUT|EAI_AGAIN|network).*")) {
            return "네트워크 문제로 Microsoft 서버에 연결하지 못했어요. 잠시 후 다시 시도해주세요.";
        }
        if (m.matches("(?i).*(does not have.*Minecraft|doesn'?t own|xbox live account).*")) {
            return "이 Microsoft 계정에는 마인크래프트 자바 에디션이 연결되어 있지 않아요.";
        }
        return "로그인 처리 중 알 수 없는 오류가 발생했어요. 다시 시도해도 안 되면 관리자에게 문의해주세요.";
    }

    private ResponseEntity<?> redirectTo(String path) {
        return ResponseEntity.status(302).location(URI.create(path)).build();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}
