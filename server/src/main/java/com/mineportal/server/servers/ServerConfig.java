package com.mineportal.server.servers;

import com.mineportal.server.connection.ChatMessage;
import com.mineportal.server.status.StatusEntry;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * 저장된 서버 하나에 대한 인메모리 런타임 상태로, ServerRegistry에서 id로 키가 매겨진다.
 * 시작 시 DB(host/port/version)로부터 복원되며, status/phase/connected/messages는
 * 프로세스 재시작 시 살아있는 연결이 유지될 수 없으므로 새로 초기화된다.
 * src/servers.ts의 ServerConfig를 포팅한 것.
 */
public class ServerConfig {

    private static final int MAX_HISTORY = 200;

    public final String id;
    public final String host;
    public final int port;
    public final String version;

    /** 자유 형식의 표시용 상태 문자열 (클라이언트로부터 오거나, 아무 시도도 없었으면 "idle"). */
    public volatile String status = "idle";
    /** 새 연결 시도를 허용할지를 결정하는 대략적인 상태 — status는 그냥 표시용 텍스트일 뿐이다. */
    public volatile String phase = "idle"; // "idle" | "active" | "closed"
    /** 연결이 실제로 play 상태에 도달했으면 true. */
    public volatile boolean connected = false;

    private final List<StatusEntry> statusHistory = Collections.synchronizedList(new LinkedList<>());
    private final List<ChatMessage> messages = Collections.synchronizedList(new LinkedList<>());

    public ServerConfig(String id, String host, int port, String version) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.version = version;
    }

    public void pushStatusHistory(StatusEntry entry) {
        statusHistory.add(entry);
        if (statusHistory.size() > MAX_HISTORY) statusHistory.remove(0);
    }

    public void pushMessage(ChatMessage message) {
        messages.add(message);
        if (messages.size() > MAX_HISTORY) messages.remove(0);
    }

    public List<StatusEntry> getStatusHistory() {
        return List.copyOf(statusHistory);
    }

    public List<ChatMessage> getMessages() {
        return List.copyOf(messages);
    }

}
