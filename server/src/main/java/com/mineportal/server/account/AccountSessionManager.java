package com.mineportal.server.account;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AccountSessionManager {

    private final Map<String, AccountState> sessions = new ConcurrentHashMap<>();

    public AccountState get(String sid) {
        return sessions.computeIfAbsent(sid, k -> new AccountState());
    }

    public void remove(String sid) {
        sessions.remove(sid);
    }

    /** ownerId(로그인된 마인크래프트 프로필 id)로 세션을 역으로 찾는다 — 데스크톱 앱이
     * 페어링될 때, 그 계정이 이미 어느 브라우저 탭에서 로그인해뒀는지 확인하는 데 쓰인다. */
    public AccountState findByOwnerId(String ownerId) {
        for (AccountState state : sessions.values()) {
            if (state.profile != null && ownerId.equals(state.profile.id())) return state;
        }
        return null;
    }

}
