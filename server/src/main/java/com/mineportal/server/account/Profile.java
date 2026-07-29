package com.mineportal.server.account;

/** id는 하이픈 없는 32자 hex 마인크래프트 프로필 UUID다 — 예전 Node 백엔드의
 * Profile.id 형태와 일치한다 (서버 목록 소유권이 정확히 이 문자열을 키로 사용한다). */
public record Profile(String id, String name) {
}
