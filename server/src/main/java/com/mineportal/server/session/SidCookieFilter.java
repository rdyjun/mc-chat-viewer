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
 * Issues an opaque random session id ("sid") cookie to every visitor on first request. This
 * replaces the old Node backend's "uid" cookie, which held the user's Minecraft profile id
 * directly and was trusted as-is (forgeable — anyone could set document.cookie to someone
 * else's id and see their server list). "sid" is never itself an identity claim: it's only a
 * key into server-held session state (see AccountSessionManager), and it is effectively
 * unguessable (128-bit random UUID), so it can't be used to impersonate another session.
 */
@Component
public class SidCookieFilter extends HttpFilter {

    public static final String COOKIE_NAME = "sid";
    public static final String REQUEST_ATTRIBUTE = "sid";
    private static final int MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String sid = readCookie(req);
        if (sid == null) {
            sid = UUID.randomUUID().toString();
            Cookie cookie = new Cookie(COOKIE_NAME, sid);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(MAX_AGE_SECONDS);
            cookie.setSecure(req.isSecure());
            res.addCookie(cookie);
        }
        req.setAttribute(REQUEST_ATTRIBUTE, sid);
        chain.doFilter(req, res);
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
