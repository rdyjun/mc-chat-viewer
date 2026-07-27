package com.mineportal.server.ping;

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
import java.util.regex.Pattern;

/**
 * Server List Ping — the same lightweight, loginless status query real launchers use to show a
 * server's player count/MOTD in the multiplayer list. Ported 1:1 from the previous Node
 * implementation (src/protocol/serverPing.ts); never throws for a normal "server unreachable"
 * outcome, only for genuinely unexpected states.
 */
@Service
public class PingService {

    private static final int PING_TIMEOUT_MS = 3000;
    // The status handshake only echoes this back inside the JSON response (as the client's
    // requested version) — any recent protocol number works regardless of the server's actual
    // version, since Server List Ping predates per-version protocol negotiation for status queries.
    private static final int HANDSHAKE_PROTOCOL_VERSION = 767;

    private static final Pattern IPV4_127 = Pattern.compile("^127\\.");
    private static final Pattern IPV4_10 = Pattern.compile("^10\\.");
    private static final Pattern IPV4_192_168 = Pattern.compile("^192\\.168\\.");
    private static final Pattern IPV4_169_254 = Pattern.compile("^169\\.254\\.");
    private static final Pattern IPV4_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[01])\\.");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** True for hostnames/IPs that only make sense on our own private network — blocking these
     * keeps this public, unauthenticated endpoint (and the actual connect path) from being used
     * as an SSRF probe against internal infrastructure. */
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
        writeVarInt(body, 0x00); // packet id
        writeVarInt(body, HANDSHAKE_PROTOCOL_VERSION);
        writeString(body, host);
        body.write((port >>> 8) & 0xFF);
        body.write(port & 0xFF);
        writeVarInt(body, 1); // next state: status
        return body.toByteArray();
    }

    private byte[] buildStatusRequestPacket() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00); // packet id, empty body
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

    /** Small cursor for reading VarInt/String fields out of an already-buffered packet body. */
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
