package com.mineportal.server.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 방문자가 처음 요청을 보낼 때마다 불투명한 무작위 세션 id("sid") 쿠키를 발급한다. 이는
 * 예전 Node 백엔드의 "uid" 쿠키를 대체하는데, 그 쿠키는 사용자의 마인크래프트 프로필 id를
 * 그대로 담고 있어 검증 없이 신뢰되었다(위조 가능 — 누구든 document.cookie에 남의 id를
 * 넣으면 그 사람의 서버 목록을 볼 수 있었다). "sid"는 그 자체로는 신원을 증명하지 않는다:
 * 서버가 들고 있는 세션 상태(AccountSessionManager 참고)로 들어가는 키일 뿐이며, 사실상
 * 추측이 불가능해서(128비트 무작위 UUID) 다른 세션을 사칭하는 데 쓰일 수 없다.
 */
@Component
public class SidCookieFilter extends HttpFilter {

    public static final String COOKIE_NAME = "sid";
    public static final String REQUEST_ATTRIBUTE = "sid";
    private static final int MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

    // 이 배포 환경의 nginx는 X-Forwarded-Proto를 전달하지 않기 때문에 req.isSecure()를
    // 믿을 수 없다 — 그래서 쿠키에 Secure 플래그를 붙일지는 명시적으로 제어한다. 운영
    // 환경은 COOKIE_SECURE=true로 설정하고, 로컬 http 개발 환경은 false로 둬야 브라우저가
    // Set-Cookie 자체를 버리지 않는다.
    private static final boolean COOKIE_SECURE = Boolean.parseBoolean(
            System.getenv().getOrDefault("COOKIE_SECURE", "true"));

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String sid = readCookie(req);
        if (sid == null) {
            sid = UUID.randomUUID().toString();
            issueCookie(res, sid);
        }
        req.setAttribute(REQUEST_ATTRIBUTE, sid);
        chain.doFilter(req, res);
    }

    /** AuthController에서도 로그인 시도가 시작되는 시점에 쿠키를 새 sid로 교체할 때
     * 사용한다. 이렇게 하면 공격자가 로그인 전에 피해자 브라우저에 심어둔 sid가 인증된
     * 세션까지 이어지지 못한다 (세션 고정 공격 방지). */
    public static void issueCookie(HttpServletResponse res, String sid) {
        Cookie cookie = new Cookie(COOKIE_NAME, sid);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(MAX_AGE_SECONDS);
        cookie.setSecure(COOKIE_SECURE);
        cookie.setAttribute("SameSite", "Lax");
        res.addCookie(cookie);
    }

    private String readCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

}
