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

}
