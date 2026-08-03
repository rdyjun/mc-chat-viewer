package com.mineportal.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mineportal.auth.Account;
import com.mineportal.mc.ChatClient;
import com.mineportal.util.AppPaths;

import javax.swing.JOptionPane;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 이 앱의 유일한 네트워크 진입점. 백엔드로 아웃바운드 WS(/app-ws)를 열어두고 그 안에서만
 * 동작한다 — 로컬 포트를 여는 것도, 사용자에게 GUI를 보여주는 것도 없다(최초 페어링에
 * 필요한 코드 입력 창 하나만 예외). 브라우저가 백엔드에 보내는 연결/해제/채팅 요청을
 * 백엔드가 이 커넥션으로 그대로 넘겨주면, 실제 마인크래프트 프로토콜 접속은 여기(사용자
 * PC, 사용자 실IP)에서 ChatClient가 수행하고, 결과(연결됨/끊김/채팅)를 다시 이 커넥션으로
 * 올려보낸다.
 */
public final class BackendClient {

    private static final String DEFAULT_URL = "wss://mineportal.kr/app-ws";
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);

    private final String wsUrl = System.getenv().getOrDefault("MINEPORTAL_WS_URL", DEFAULT_URL);
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mineportal-backend-client");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, ChatClient> activeClients = new ConcurrentHashMap<>();
    private volatile Account account;
    private volatile WebSocket webSocket;
    private volatile boolean pairingPromptShowing = false;

    public interface StatusListener {
        void onConnectionStateChanged(boolean connectedToBackend);
    }

    private final StatusListener statusListener;

    public BackendClient(StatusListener statusListener) {
        this.statusListener = statusListener;
    }

    public void start() {
        openSocket();
    }

    private void openSocket() {
        WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        builder.buildAsync(URI.create(wsUrl), new Listener())
                .exceptionally(ex -> {
                    scheduleReconnect();
                    return null;
                });
    }

    private void scheduleReconnect() {
        statusListener.onConnectionStateChanged(false);
        scheduler.schedule(this::openSocket, RECONNECT_DELAY.toSeconds(), TimeUnit.SECONDS);
    }

    private void send(Map<String, Object> payload) {
        WebSocket ws = webSocket;
        if (ws == null) return;
        ws.sendText(gson.toJson(payload), true);
    }

    private void authenticate() {
        String deviceToken = readDeviceToken();
        if (deviceToken != null) {
            send(Map.of("type", "resume", "deviceToken", deviceToken));
        } else {
            promptForPairingCode();
        }
    }

    private void promptForPairingCode() {
        if (pairingPromptShowing) return;
        pairingPromptShowing = true;
        new Thread(() -> {
            try {
                String code = JOptionPane.showInputDialog(null,
                        "웹사이트(mineportal.kr)에 로그인한 뒤 \"데스크톱 앱 연동\"에서 발급받은 코드를 입력하세요:",
                        "마인포탈 데스크톱 연동", JOptionPane.QUESTION_MESSAGE);
                pairingPromptShowing = false;
                if (code != null && !code.isBlank()) {
                    send(Map.of("type", "pair", "code", code.trim().toUpperCase()));
                }
            } finally {
                pairingPromptShowing = false;
            }
        }, "mineportal-pair-prompt").start();
    }

    private String readDeviceToken() {
        try {
            return Files.readString(AppPaths.deviceFile(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveDeviceToken(String token) {
        try {
            Files.writeString(AppPaths.deviceFile(), token, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private void handleMessage(String json) {
        JsonObject obj;
        try {
            obj = gson.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            return;
        }
        String type = obj.has("type") ? obj.get("type").getAsString() : "";
        switch (type) {
            case "paired" -> saveDeviceToken(obj.get("deviceToken").getAsString());
            case "pair-required", "pair-failed" -> promptForPairingCode();
            case "token" -> account = new Account(
                    UUID.fromString(obj.get("uuid").getAsString()),
                    obj.get("name").getAsString(),
                    obj.get("accessToken").getAsString());
            case "connect" -> handleConnect(obj.get("serverId").getAsString(),
                    obj.get("host").getAsString(), obj.get("port").getAsInt());
            case "disconnect" -> handleDisconnect(obj.get("serverId").getAsString());
            case "send-chat" -> handleSendChat(obj.get("serverId").getAsString(), obj.get("message").getAsString());
            default -> { /* 알 수 없는 메시지 타입은 무시 */ }
        }
    }

    private void handleConnect(String serverId, String host, int port) {
        Account acc = account;
        if (acc == null) return; // 토큰이 아직 안 왔으면(로그인 전) 할 수 있는 게 없다
        if (activeClients.containsKey(serverId)) return;

        ChatClient client = new ChatClient(acc, new ChatClient.Listener() {
            @Override
            public void onConnected() {
                send(Map.of("type", "connected", "serverId", serverId));
            }

            @Override
            public void onChat(String line) {
                send(Map.of("type", "chat", "serverId", serverId, "username", acc.name, "message", line));
            }

            @Override
            public void onDisconnected(String reason) {
                activeClients.remove(serverId);
                send(Map.of("type", "disconnected", "serverId", serverId, "reason", reason == null ? "" : reason));
            }
        });
        activeClients.put(serverId, client);
        try {
            client.connect(host, port);
        } catch (Exception e) {
            activeClients.remove(serverId);
            send(Map.of("type", "disconnected", "serverId", serverId, "reason", String.valueOf(e.getMessage())));
        }
    }

    private void handleDisconnect(String serverId) {
        ChatClient client = activeClients.remove(serverId);
        if (client != null) client.disconnect();
    }

    private void handleSendChat(String serverId, String message) {
        ChatClient client = activeClients.get(serverId);
        if (client != null) client.sendChat(message);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            statusListener.onConnectionStateChanged(true);
            authenticate();
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            ws.request(1);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(message);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            webSocket = null;
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            webSocket = null;
            scheduleReconnect();
        }
    }

}
