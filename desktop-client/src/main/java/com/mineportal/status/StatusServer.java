package com.mineportal.status;

import com.mineportal.BuildInfo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Tiny loopback-only HTTP server so the web dashboard can detect that the desktop
 * client is running on this machine and read its live status via a plain fetch().
 */
public final class StatusServer {

    /** Fixed port the web page polls; keep in sync with public/index.html. */
    public static final int PORT = 47893;

    private final BooleanSupplier loggedIn;
    private final BooleanSupplier connected;
    private final Supplier<String> connectedServer;

    private HttpServer server;

    public StatusServer(BooleanSupplier loggedIn, BooleanSupplier connected, Supplier<String> connectedServer) {
        this.loggedIn = loggedIn;
        this.connected = connected;
        this.connectedServer = connectedServer;
    }

    public void start() {
        try {
            InetSocketAddress addr = new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), PORT);
            server = HttpServer.create(addr, 0);
            server.createContext("/status", this::handleStatus);
            server.setExecutor(null);
            server.start();
        } catch (IOException ex) {
            // Another instance is likely already running on this port; that's fine,
            // the web page will detect that one instead.
            server = null;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String server = connectedServer.get();
        String json = "{"
                + "\"running\":true,"
                + "\"version\":\"" + BuildInfo.VERSION + "\","
                + "\"loggedIn\":" + loggedIn.getAsBoolean() + ","
                + "\"connected\":" + connected.getAsBoolean() + ","
                + "\"server\":" + (server == null ? "null" : "\"" + escape(server) + "\"")
                + "}";

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
