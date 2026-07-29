package com.mineportal.server.connection;

import com.mineportal.server.account.AccountState;
import com.mineportal.server.servers.ServerConfig;
import com.mineportal.server.ws.EventBroadcaster;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.raphimc.minecraftauth.step.java.StepPlayerCertificates;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatSessionUpdatePacket;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCProtocolLib을 통한 실제 마인크래프트 프로토콜 연결로, desktop-client의
 * com.mineportal.mc.ChatClient(이미 검증된 동일한 connect/listener/send-chat 구조)를 포팅한 것.
 * 채팅 서명은 예전 Node 백엔드(commit 113bb9b)에서 검증된 서명 바이트 레이아웃을 그대로
 * 포팅했고, 서명 인증서는 로그인 시 MinecraftAuth가 이미 발급받아둔 것을 사용한다.
 */
@Service
public class McConnectionManager {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final EventBroadcaster broadcaster;
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ChatSigner> chatSigners = new ConcurrentHashMap<>();

    public McConnectionManager(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void connect(ServerConfig server, AccountState account) {
        new Thread(() -> doConnect(server, account), "mc-connect-" + server.id).start();
    }

    private void doConnect(ServerConfig server, AccountState account) {
        var mcProfile = account.fullSession.getMcProfile();
        GameProfile profile = new GameProfile(mcProfile.getId(), mcProfile.getName());
        MinecraftProtocol protocol = new MinecraftProtocol(profile, mcProfile.getMcToken().getAccessToken());

        ClientSession client = ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(server.host, server.port))
                .setProtocol(protocol)
                .create();
        client.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());

        // 서명 인증서가 있고 만료되지 않았으면 채팅 서명을 활성화한다 — 없으면 서명 없이
        // 보내고, secure-chat을 강제하는 서버는 chat.disabled.missingProfileKey로 조용히
        // 거부한다(크래시나 킥 없이).
        StepPlayerCertificates.PlayerCertificates certificates = account.fullSession.getPlayerCertificates();
        if (certificates != null && !certificates.isExpired()) {
            chatSigners.put(server.id, new ChatSigner(profile.getId(), certificates));
        } else {
            chatSigners.remove(server.id);
        }

        String accountName = mcProfile.getName();
        client.addListener(new SessionAdapter() {
            private boolean joinAnnounced = false;

            @Override
            public void packetReceived(Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    // 서버에 우리 서명 키를 등록한다 — 이게 있어야 서버가 이후 서명된 채팅을
                    // 받아준다. play 상태 진입 직후, 실제 채팅 이전에 보내야 한다.
                    ChatSigner signer = chatSigners.get(server.id);
                    if (signer != null) {
                        s.send(signer.sessionUpdatePacket());
                    }
                    return;
                }
                String text;
                if (packet instanceof ClientboundSystemChatPacket p) {
                    text = plain(p.getContent());
                } else if (packet instanceof ClientboundPlayerChatPacket p) {
                    Component body = p.getUnsignedContent();
                    String message = body != null ? plain(body) : p.getContent();
                    text = "<" + plain(p.getName()) + "> " + message;
                } else {
                    return;
                }
                if (!joinAnnounced) {
                    joinAnnounced = true;
                    server.connected = true;
                    broadcaster.setStatus(server, "connected", true);
                }
                // 서버가 번역 키를 그대로 보내는 경우가 있다("multiplayer.player.joined",
                // "chat.disabled.invalid_command_signature" 등) — Adventure의
                // PlainTextComponentSerializer는 번역을 해석하지 못해 키 원문을 그대로
                // 반환하므로, 이런 미해석 키는 사용자에게 노출하지 않고 무시한다.
                if (isRawTranslationKey(text)) return;
                broadcaster.chatReceived(server, new ChatMessage(accountName, text, System.currentTimeMillis()));
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                sessions.remove(server.id);
                chatSigners.remove(server.id);
                server.phase = "closed";
                server.connected = false;
                broadcaster.setStatus(server, "disconnected: " + plain(event.getReason()), true);
            }
        });

        sessions.put(server.id, client);
        client.connect();
    }

    /** 주어진 서버 id의 살아있는 연결을 강제로 끊는다 — 브라우저의 마지막 탭이 닫혔을 때
     * 좀비로 남을 MCProtocolLib 연결을 정리하기 위해 사용된다. 실제 정리(맵에서 제거,
     * phase/connected 갱신, 브로드캐스트)는 위 disconnected() 콜백이 처리한다. */
    public void disconnect(String serverId) {
        ClientSession session = sessions.get(serverId);
        if (session != null) {
            session.disconnect("사용자 세션이 종료되어 연결을 닫았어요");
        }
    }

    public void sendChat(String serverId, String message) {
        ClientSession session = sessions.get(serverId);
        if (session == null || !session.isConnected()) throw new NotConnectedException();

        // "/"로 시작하면 일반 채팅이 아니라 명령어다 — ServerboundChatPacket으로 보내면
        // 서버가 그냥 평문 채팅으로 취급해서 명령어가 실행되지 않는다.
        if (message.startsWith("/")) {
            session.send(new ServerboundChatCommandPacket(message.substring(1)));
            return;
        }

        ChatSigner signer = chatSigners.get(serverId);
        long timestampMs = Instant.now().toEpochMilli();
        if (signer != null) {
            session.send(signer.signedChatPacket(message, timestampMs));
        } else {
            session.send(new ServerboundChatPacket(message, timestampMs, 0L, null, 0, new BitSet(), 0));
        }
    }

    private static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

    /** "multiplayer.player.joined", "chat.disabled.invalid_command_signature"처럼
     * 점(.)으로 구분된 소문자 식별자만으로 이뤄진, 해석되지 않은 번역 키 형태인지 판별한다. */
    private static boolean isRawTranslationKey(String text) {
        return text.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+");
    }

    /** Mojang이 발급한 세션별 RSA 키로 채팅 메시지를 서명한다 — enforce-secure-profile=true인
     * 서버가 우리 메시지를 거부하지 않고 받아주게 하기 위함. 서명 대상 바이트 레이아웃은
     * 예전(하드코딩 프로토콜 클라이언트) Node 백엔드의 chatSigning.ts를 그대로 포팅한 것으로,
     * 실제 라이브 서버에 대해 검증됐던 레이아웃이다. */
    private static final class ChatSigner {
        private final UUID senderId;
        private final UUID sessionId = UUID.randomUUID();
        private final StepPlayerCertificates.PlayerCertificates certificates;
        private final AtomicInteger messageIndex = new AtomicInteger(0);
        private final SecureRandom random = new SecureRandom();

        ChatSigner(UUID senderId, StepPlayerCertificates.PlayerCertificates certificates) {
            this.senderId = senderId;
            this.certificates = certificates;
        }

        ServerboundChatSessionUpdatePacket sessionUpdatePacket() {
            return new ServerboundChatSessionUpdatePacket(
                    sessionId, certificates.getExpireTimeMs(), certificates.getPublicKey(), certificates.getPublicKeySignature());
        }

        /** 레이아웃: 버전 마커(4) + 발신자 UUID(16바이트) + 세션 UUID(16바이트) +
         * 메시지 인덱스(4) + salt(8) + 타임스탬프(epoch 초, 8) + 메시지 길이(4) + 메시지
         * UTF-8 바이트 + 마지막으로 확인한 메시지 개수(4, 여기선 추적하지 않으므로 항상 0). */
        ServerboundChatPacket signedChatPacket(String message, long timestampMs) {
            int index = messageIndex.getAndIncrement();
            long salt = random.nextLong();
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            ByteBuffer buf = ByteBuffer.allocate(4 + 16 + 16 + 4 + 8 + 8 + 4 + messageBytes.length + 4);
            buf.putInt(1);
            putUuidBytes(buf, senderId);
            putUuidBytes(buf, sessionId);
            buf.putInt(index);
            buf.putLong(salt);
            buf.putLong(timestampMs / 1000);
            buf.putInt(messageBytes.length);
            buf.put(messageBytes);
            buf.putInt(0);

            byte[] signature;
            try {
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(certificates.getPrivateKey());
                sig.update(buf.array());
                signature = sig.sign();
            } catch (Exception e) {
                throw new RuntimeException("채팅 메시지 서명에 실패했어요", e);
            }

            return new ServerboundChatPacket(message, timestampMs, salt, signature, 0, new BitSet(), 0);
        }

        private static void putUuidBytes(ByteBuffer buf, UUID uuid) {
            buf.putLong(uuid.getMostSignificantBits());
            buf.putLong(uuid.getLeastSignificantBits());
        }
    }

}
