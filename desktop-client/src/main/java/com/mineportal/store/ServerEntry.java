package com.mineportal.store;

/** A saved server target. `version` is informational; the client speaks one bundled protocol. */
public class ServerEntry {

    public String name = "";
    public String host = "";
    public int port = 25565;
    public String version = "";

    public ServerEntry() {
    }

    public ServerEntry(String name, String host, int port, String version) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.version = version;
    }

    public String label() {
        String base = (name == null || name.isBlank()) ? host : name;
        return base + " (" + host + ":" + port + ")";
    }

    @Override
    public String toString() {
        return label();
    }
}
