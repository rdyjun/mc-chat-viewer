package com.mineportal.server.status;

/** 프론트엔드가 GET /api/ping에서 이미 기대하고 있는 JSON 형태를 그대로 따른다. */
public record PingResult(
        boolean online,
        Integer playersOnline,
        Integer playersMax,
        String motd,
        String version,
        String error
) {

    public static PingResult offline(String error) {
        return new PingResult(false, null, null, null, null, error);
    }

    public static PingResult online(Integer playersOnline, Integer playersMax, String motd, String version) {
        return new PingResult(true, playersOnline, playersMax, motd, version, null);
    }

}
