package com.mineportal.server.account;

import net.raphimc.minecraftauth.step.java.session.StepFullJavaSession;

/**
 * 세션(브라우저)별 로그인 상태 — 예전 Node 백엔드에서 이 값이 모든 방문자가 공유하는
 * 프로세스 전역 변수 하나였던 버그를 실제로 고친 것이다. "sid"(SidCookieFilter 참고)마다
 * 하나의 인스턴스가 있으며, AccountSessionManager가 이를 보관한다.
 */
public class AccountState {

    public volatile String status = "logged-out"; // "logged-out" | "logging-in" | "logged-in" | "error"
    public volatile Profile profile;
    public volatile String error;

    /** MinecraftAuth의 전체 세션(액세스 토큰, 채팅 서명용 인증서) — 서버 측에만 보관하며
     * 클라이언트로는 절대 전송하지 않는다. (향후) 프로토콜 커넥터에서 사용될 예정. */
    public volatile StepFullJavaSession.FullJavaSession fullSession;

}
