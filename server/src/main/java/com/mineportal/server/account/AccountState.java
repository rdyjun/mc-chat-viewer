package com.mineportal.server.account;

import net.raphimc.minecraftauth.step.java.session.StepFullJavaSession;

/**
 * Per-session (per-browser) login state — the actual fix for the old Node backend's bug where
 * this was a single process-global variable shared by every visitor. One instance per "sid"
 * (see SidCookieFilter), held in AccountSessionManager.
 */
public class AccountState {

    public volatile String status = "logged-out"; // "logged-out" | "logging-in" | "logged-in" | "error"
    public volatile Profile profile;
    public volatile String error;

    /** The full MinecraftAuth session (access token, chat-signing certificate) — kept
     * server-side only, never sent to the client. Used by the (future) protocol connector. */
    public volatile StepFullJavaSession.FullJavaSession fullSession;

}
