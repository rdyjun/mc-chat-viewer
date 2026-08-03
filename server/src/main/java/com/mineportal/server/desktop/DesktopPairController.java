package com.mineportal.server.desktop;

import com.mineportal.server.account.AccountSessionManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DesktopPairController {

    private final AccountSessionManager accountSessions;
    private final DesktopConnectionManager desktopConnections;

    public DesktopPairController(AccountSessionManager accountSessions, DesktopConnectionManager desktopConnections) {
        this.accountSessions = accountSessions;
        this.desktopConnections = desktopConnections;
    }

    /** 로그인된 브라우저 세션이 데스크톱 앱과 연동할 1회용 코드를 요청한다. */
    @PostMapping("/api/desktop/pair")
    public ResponseEntity<?> pair(@RequestAttribute("sid") String sid) {
        var profile = accountSessions.get(sid).profile;
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Log in before pairing the desktop app"));
        }
        String code = desktopConnections.createPairingCode(profile.id());
        return ResponseEntity.ok(Map.of("code", code, "expiresInSeconds", 300));
    }

}
