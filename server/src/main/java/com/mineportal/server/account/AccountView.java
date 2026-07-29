package com.mineportal.server.account;

import java.util.HashMap;
import java.util.Map;

/** AccountState의 공개용 뷰 — 원본 토큰이나 개인 서명 키는 절대 포함하지 않는다. */
public final class AccountView {

    private AccountView() {
    }

    public static Map<String, Object> of(AccountState state) {
        Map<String, Object> view = new HashMap<>();
        view.put("status", state.status);
        if (state.profile != null) view.put("profile", Map.of("id", state.profile.id(), "name", state.profile.name()));
        if (state.error != null) view.put("error", state.error);
        return view;
    }

}
