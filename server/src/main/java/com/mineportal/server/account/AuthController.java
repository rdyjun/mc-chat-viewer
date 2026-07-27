package com.mineportal.server.account;

import com.mineportal.server.ws.EventBroadcaster;
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

    // Same tenant/endpoint the existing Azure app registration is already configured for
    // (matches the old Node backend's msal-node `authority: ".../consumers"` setting).
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

    // OAuth CSRF nonce -> the sid that initiated it, so the callback knows which session's
    // login this is (the old Node backend only needed a bare Set since it had one global
    // account; now each session gets its own AccountState).
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
            @RequestParam(name = "error_description", required = false) String errorDescription
    ) {
        if (error != null) {
            return redirectTo("/?loginError=" + encode(errorDescription != null ? errorDescription : error));
        }
        String sid = state != null ? pendingLoginStates.remove(state) : null;
        if (sid == null || code == null) {
            return ResponseEntity.status(400).body("Invalid or expired login attempt. Please try signing in again.");
        }

        completeLoginAsync(sid, code);
        return redirectTo("/");
    }

    // Runs the actual Microsoft/Xbox Live/XSTS/Minecraft exchange on its own thread so the HTTP
    // response returns immediately — the frontend picks up progress via the "account" WebSocket
    // event, same as the old Node backend's fire-and-forget completeMicrosoftLogin().
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
            } catch (Exception e) {
                state.status = "error";
                state.error = translateAuthError(e.getMessage());
            }
            broadcaster.refreshAccountFor(sid);
        }, "mineportal-login-" + sid).start();
    }

    /**
     * MinecraftAuth/MSAL-style errors surface as raw English exception messages. Translate the
     * ones we actually see in practice into a plain-Korean explanation, ported from the old
     * Node backend's account.ts translateAuthError().
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
