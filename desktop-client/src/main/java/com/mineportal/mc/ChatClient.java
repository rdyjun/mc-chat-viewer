package com.mineportal.mc;

import com.mineportal.auth.Account;
import com.mineportal.util.Text;
import net.kyori.adventure.text.Component;
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

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.BitSet;

/**
 * Wraps an MCProtocolLib client session for a single server connection.
 * Callbacks fire on network threads; the UI is responsible for marshalling to the EDT.
 */
public final class ChatClient {

    /** Connection + chat events. All invoked off the Swing EDT. */
    public interface Listener {
        void onConnected();

        void onChat(String line);

        void onDisconnected(String reason);
    }

    private final Account account;
    private final Listener listener;
    private volatile ClientSession session;

    public ChatClient(Account account, Listener listener) {
        this.account = account;
        this.listener = listener;
    }

    public void connect(String host, int port) {
        GameProfile profile = new GameProfile(account.uuid, account.name);
        MinecraftProtocol protocol = new MinecraftProtocol(profile, account.accessToken);

        ClientSession client = ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(host, port))
                .setProtocol(protocol)
                .create();
        client.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                if (packet instanceof ClientboundSystemChatPacket p) {
                    listener.onChat(Text.plain(p.getContent()));
                } else if (packet instanceof ClientboundPlayerChatPacket p) {
                    Component body = p.getUnsignedContent();
                    String message = body != null ? Text.plain(body) : p.getContent();
                    listener.onChat("<" + Text.plain(p.getName()) + "> " + message);
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                session = null;
                listener.onDisconnected(Text.plain(event.getReason()));
            }
        });

        this.session = client;
        // connect() establishes the TCP connection; login proceeds asynchronously.
        // onConnected is signalled here so the UI can switch state; a failure surfaces
        // via disconnected().
        client.connect();
        listener.onConnected();
    }

    public boolean isConnected() {
        ClientSession s = session;
        return s != null && s.isConnected();
    }

    public void sendChat(String message) {
        ClientSession s = session;
        if (s == null || !s.isConnected()) {
            return;
        }
        s.send(new ServerboundChatPacket(
                message,
                Instant.now().toEpochMilli(),
                0L,
                null,
                0,
                new BitSet(),
                0));
    }

    public void disconnect() {
        ClientSession s = session;
        session = null;
        if (s != null) {
            s.disconnect(Component.text("Disconnected by user"));
        }
    }
}
