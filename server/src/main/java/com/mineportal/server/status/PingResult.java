package com.mineportal.server.status;

/** Mirrors the JSON shape the frontend already expects from GET /api/ping. */
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
