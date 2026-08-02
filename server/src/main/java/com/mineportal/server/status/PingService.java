package com.mineportal.server.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Server List Ping —실제 런처들이 멀티플레이 목록에서 서버의 접속자 수/MOTD를 보여줄 때
 * 사용하는, 로그인이 필요 없는 가벼운 상태 조회와 동일한 방식이다. 예전 Node 구현
 * (src/protocol/serverPing.ts)에서 1:1로 포팅했으며, "서버에 연결할 수 없음"이라는 정상적인
 * 결과에 대해서는 절대 예외를 던지지 않고, 진짜로 예상치 못한 상태에서만 던진다.
 */
@Service
public class PingService {

    private static final int PING_TIMEOUT_MS = 3000;
    // status 핸드셰이크는 이 값을 JSON 응답 안에(클라이언트가 요청한 버전으로) 그대로
    // 돌려줄 뿐이다 — Server List Ping은 상태 조회에 있어 버전별 프로토콜 협상이
    // 도입되기 이전 방식이라, 서버의 실제 버전과 무관하게 최근 프로토콜 번호면 아무거나
    // 통한다.
    private static final int HANDSHAKE_PROTOCOL_VERSION = 767;

    private static final Pattern IPV4_127 = Pattern.compile("^127\\.");
    private static final Pattern IPV4_10 = Pattern.compile("^10\\.");
    private static final Pattern IPV4_192_168 = Pattern.compile("^192\\.168\\.");
    private static final Pattern IPV4_169_254 = Pattern.compile("^169\\.254\\.");
    private static final Pattern IPV4_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[01])\\.");

    // 여러 사용자가 같은 서버를 짧은 시간 안에 반복 조회해도 실제 TCP ping은 한 번만
    // 나가도록 host:port 단위로 결과를 잠깐 캐싱한다. ConcurrentHashMap#compute는 같은
    // 키에 대해서는 호출을 직렬화하므로, TTL이 지나기 전 동시에 들어온 요청들도 중복으로
    // 소켓을 열지 않고 진행 중인 ping의 결과를 함께 기다렸다가 공유해서 받는다.
    private static final long CACHE_TTL_MS = 15_000;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(PingResult result, long expiresAt) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 우리 자신의 사설망 안에서만 의미가 있는 호스트명/IP인 경우 true를 반환한다 — 이를
     * 차단함으로써, 인증 없이 공개된 이 엔드포인트(그리고 실제 connect 경로)가 내부
     * 인프라를 대상으로 한 SSRF 탐지 도구로 악용되지 못하게 한다. */
    public boolean isPrivateHost(String host) {
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".local")) return true;
        if (!isIpAddress(h)) return false;
        return IPV4_127.matcher(h).find()
                || IPV4_10.matcher(h).find()
                || IPV4_192_168.matcher(h).find()
                || IPV4_169_254.matcher(h).find()
                || IPV4_172.matcher(h).find()
                || h.equals("::1");
    }

    private boolean isIpAddress(String host) {
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || host.contains(":");
    }

    public PingResult ping(String host, int port) {
        String key = host.toLowerCase() + ":" + port;
        long now = System.currentTimeMillis();
        return cache.compute(key, (k, existing) -> {
            if (existing != null && existing.expiresAt() > now) {
                return existing;
            }
            return new CacheEntry(doPing(host, port), now + CACHE_TTL_MS);
        }).result();
    }

    private PingResult doPing(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PING_TIMEOUT_MS);
            socket.setSoTimeout(PING_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            writeFrame(out, buildHandshakePacket(host, port));
            writeFrame(out, buildStatusRequestPacket());

            InputStream in = socket.getInputStream();
            int frameLength = readVarInt(in);
            byte[] body = in.readNBytes(frameLength);
            if (body.length < frameLength) {
                return PingResult.offline("connection closed before full response");
            }

            ByteArrayInputStreamCursor cursor = new ByteArrayInputStreamCursor(body);
            int packetId = cursor.readVarInt();
            if (packetId != 0x00) {
                return PingResult.offline("unexpected packet id " + packetId);
            }
            String json = cursor.readString();
            JsonNode root = objectMapper.readTree(json);

            JsonNode players = root.path("players");
            Integer playersOnline = players.has("online") ? players.get("online").asInt() : null;
            Integer playersMax = players.has("max") ? players.get("max").asInt() : null;
            String motd = extractMotd(root.path("description"));
            String version = root.path("version").path("name").asText(null);

            return PingResult.online(playersOnline, playersMax, motd, version);
        } catch (java.net.SocketTimeoutException e) {
            return PingResult.offline("timed out");
        } catch (IOException e) {
            return PingResult.offline(e.getMessage());
        }
    }

    private String extractMotd(JsonNode description) {
        if (description.isTextual()) return description.asText();
        if (description.has("text")) return description.get("text").asText();
        return null;
    }

    private byte[] buildHandshakePacket(String host, int port) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00); // 패킷 id
        writeVarInt(body, HANDSHAKE_PROTOCOL_VERSION);
        writeString(body, host);
        body.write((port >>> 8) & 0xFF);
        body.write(port & 0xFF);
        writeVarInt(body, 1); // 다음 상태: status
        return body.toByteArray();
    }

    private byte[] buildStatusRequestPacket() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00); // 패킷 id, 빈 본문
        return body.toByteArray();
    }

    private void writeFrame(OutputStream out, byte[] packetBody) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, packetBody.length);
        frame.write(packetBody);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int currentByte = in.read();
            if (currentByte == -1) throw new EOFException("stream closed while reading VarInt");
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too big");
        }
        return value;
    }

    /** 이미 버퍼에 담긴 패킷 본문에서 VarInt/String 필드를 읽어내기 위한 작은 커서. */
    private static final class ByteArrayInputStreamCursor {
        private final byte[] data;
        private int pos = 0;

        ByteArrayInputStreamCursor(byte[] data) {
            this.data = data;
        }

        int readVarInt() throws IOException {
            int value = 0;
            int position = 0;
            while (true) {
                if (pos >= data.length) throw new EOFException("ran out of bytes reading VarInt");
                int currentByte = data[pos++] & 0xFF;
                value |= (currentByte & 0x7F) << position;
                if ((currentByte & 0x80) == 0) break;
                position += 7;
                if (position >= 32) throw new IOException("VarInt too big");
            }
            return value;
        }

        String readString() throws IOException {
            int length = readVarInt();
            if (pos + length > data.length) throw new EOFException("string length exceeds remaining bytes");
            String s = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return s;
        }
    }

}
