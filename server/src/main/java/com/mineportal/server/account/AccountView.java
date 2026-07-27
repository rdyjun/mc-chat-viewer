package com.mineportal.server.account;

import java.util.HashMap;
import java.util.Map;

/** Public view of AccountState — never includes the raw token or private signing key. */
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
