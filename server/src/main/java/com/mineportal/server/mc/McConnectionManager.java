package com.mineportal.server.mc;

import com.mineportal.server.account.AccountState;
import com.mineportal.server.servers.ChatMessage;
import com.mineportal.server.servers.ServerConfig;
import com.mineportal.server.ws.EventBroadcaster;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real Minecraft protocol connections via MCProtocolLib, ported from desktop-client's
 * com.mineportal.mc.ChatClient (same connect/listener/send-chat shape — that implementation is
 * already proven in the shipped desktop app). Chat is sent unsigned, matching the desktop
 * client's own documented limitation; a signed-chat certificate is fetched during login but not
 * used yet.
 */
@Service
public class McConnectionManager {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final EventBroadcaster broadcaster;
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

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

        String accountName = mcProfile.getName();
        client.addListener(new SessionAdapter() {
            // MCProtocolLib has no distinct "join game" event in the basic listener API — the
            // first actual chat/system packet is our practical signal that login finished and
            // we're really in the play state, not just TCP-connected.
            private boolean joinAnnounced = false;

            @Override
            public void packetReceived(Session s, Packet packet) {
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
                broadcaster.chatReceived(server, new ChatMessage(accountName, text, System.currentTimeMillis()));
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                sessions.remove(server.id);
                server.phase = "closed";
                server.connected = false;
                broadcaster.setStatus(server, "disconnected: " + plain(event.getReason()), true);
            }
        });

        sessions.put(server.id, client);
        client.connect();
    }

    public void sendChat(String serverId, String message) {
        ClientSession session = sessions.get(serverId);
        if (session == null || !session.isConnected()) throw new NotConnectedException();
        session.send(new ServerboundChatPacket(message, Instant.now().toEpochMilli(), 0L, null, 0, new BitSet(), 0));
    }

    private static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

}
