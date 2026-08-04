package com.mineportal.mc;

import com.google.gson.Gson;
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
import org.geysermc.mcprotocollib.protocol.data.game.ArgumentSignature;
import org.geysermc.mcprotocollib.protocol.data.game.Holder;
import org.geysermc.mcprotocollib.protocol.data.game.chat.ChatType;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatSessionUpdatePacket;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps an MCProtocolLib client session for a single server connection. Ports the chat/command
 * signing and system-message translation from the backend's McConnectionManager (used for the
 * trial-mode relay) so servers behave identically whether the connection is relayed by the
 * backend or handled here directly by the desktop app.
 * Callbacks fire on network threads; the caller is responsible for marshalling to its own thread.
 */
public final class ChatClient {

    /** Connection + chat events. All invoked off the caller's thread. */
    public interface Listener {
        void onConnected();

        void onChat(String line);

        void onDisconnected(String reason);
    }

    private static final Map<String, String> LANG = loadLang();

    private final Account account;
    private final Listener listener;
    private volatile ClientSession session;
    private volatile ChatSigner chatSigner;
    private volatile CommandTree commandTree;

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

        Account.SigningCert cert = account.signingCert;
        chatSigner = (cert != null && !cert.isExpired()) ? new ChatSigner(account.uuid, cert) : null;
        commandTree = null;

        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    // Register our signing key with the server before any chat is sent, same as
                    // McConnectionManager does for the trial-mode relay.
                    ChatSigner signer = chatSigner;
                    if (signer != null) {
                        s.send(signer.sessionUpdatePacket());
                    }
                    return;
                }
                if (packet instanceof ClientboundCommandsPacket p) {
                    commandTree = new CommandTree(p.getNodes(), p.getFirstNodeIndex());
                    return;
                }
                if (packet instanceof ClientboundSystemChatPacket p) {
                    listener.onChat(plain(p.getContent()));
                } else if (packet instanceof ClientboundPlayerChatPacket p) {
                    Component body = p.getUnsignedContent();
                    String message = body != null ? plain(body) : p.getContent();
                    listener.onChat(renderChatType(p.getChatType(), plain(p.getName()), message));
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                session = null;
                chatSigner = null;
                commandTree = null;
                listener.onDisconnected(Text.plain(event.getReason()));
            }
        });

        this.session = client;
        // connect() establishes the TCP connection; login proceeds asynchronously.
        // onConnected is signalled here so the caller can switch state; a failure surfaces
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
        if (message.startsWith("/")) {
            sendCommand(s, message.substring(1));
            return;
        }
        ChatSigner signer = chatSigner;
        long timestampMs = Instant.now().toEpochMilli();
        if (signer != null) {
            s.send(signer.signedChatPacket(message, timestampMs));
        } else {
            s.send(new ServerboundChatPacket(message, timestampMs, 0L, null, 0, new BitSet(), 0));
        }
    }

    /** Same simplified command-argument matching McConnectionManager uses: walks the server's
     * Brigadier command tree and signs the single MESSAGE-parser argument if there is one. */
    private void sendCommand(ClientSession s, String command) {
        CommandTree tree = commandTree;
        ChatSigner signer = chatSigner;
        SignableArgument match = (tree != null && signer != null) ? findSignableArgument(tree, command) : null;
        if (match == null) {
            s.send(new ServerboundChatCommandPacket(command));
            return;
        }
        long timestampMs = Instant.now().toEpochMilli();
        ChatSigner.SignedArgument signed = signer.signArgument(match.name(), match.text(), timestampMs);
        s.send(new ServerboundChatCommandSignedPacket(
                command, timestampMs, signed.salt(), List.of(signed.signature()), 0, new BitSet(), (byte) 0));
    }

    public void disconnect() {
        ClientSession s = session;
        session = null;
        chatSigner = null;
        commandTree = null;
        if (s != null) {
            s.disconnect(Component.text("Disconnected by user"));
        }
    }

    private record SignableArgument(String name, String text) {
    }

    private record CommandTree(CommandNode[] nodes, int rootIndex) {
    }

    private static SignableArgument findSignableArgument(CommandTree tree, String command) {
        CommandNode[] nodes = tree.nodes();
        String[] tokens = command.split(" ");
        if (tokens.length == 0 || nodes.length == 0) return null;

        int nodeIndex = tree.rootIndex();
        int consumed = 0;
        while (consumed < tokens.length) {
            String token = tokens[consumed];
            int[] children = nodes[nodeIndex].getChildIndices();

            int literalMatch = -1;
            for (int c : children) {
                if (nodes[c].getType() == CommandType.LITERAL && nodes[c].getName().equals(token)) {
                    literalMatch = c;
                    break;
                }
            }
            if (literalMatch != -1) {
                nodeIndex = literalMatch;
                consumed++;
                continue;
            }

            int argumentMatch = -1;
            for (int c : children) {
                if (nodes[c].getType() == CommandType.ARGUMENT) {
                    argumentMatch = c;
                    break;
                }
            }
            if (argumentMatch == -1) return null;

            CommandNode argument = nodes[argumentMatch];
            if (argument.getParser() == CommandParser.MESSAGE) {
                String text = String.join(" ", Arrays.copyOfRange(tokens, consumed, tokens.length));
                return new SignableArgument(argument.getName(), text);
            }
            nodeIndex = argumentMatch;
            consumed++;
        }
        return null;
    }

    private static Map<String, String> loadLang() {
        try (var in = ChatClient.class.getResourceAsStream("/lang/ko_kr.json")) {
            if (in == null) return Map.of();
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String plain(Component component) {
        return component == null ? "" : render(component);
    }

    private static String renderChatType(Holder<ChatType> holder, String sender, String message) {
        String key;
        if (holder.isCustom()) {
            key = holder.custom().chat().translationKey();
        } else {
            key = switch (holder.id()) {
                case 1 -> "chat.type.emote"; // /me
                case 4 -> "chat.type.announcement"; // /say
                default -> "chat.type.text";
            };
        }
        String pattern = LANG.getOrDefault(key, "<%s> %s");
        try {
            return String.format(pattern, sender, message);
        } catch (Exception e) {
            return "<" + sender + "> " + message;
        }
    }

    private static String render(Component c) {
        String own;
        if (c instanceof net.kyori.adventure.text.TranslatableComponent tc) {
            String pattern = LANG.getOrDefault(tc.key(), tc.key());
            Object[] args = tc.args().stream().map(ChatClient::render).toArray();
            try {
                own = String.format(pattern, args);
            } catch (Exception e) {
                StringBuilder fallback = new StringBuilder(pattern);
                for (Object arg : args) fallback.append(' ').append(arg);
                own = fallback.toString();
            }
        } else if (c instanceof net.kyori.adventure.text.TextComponent tx) {
            own = tx.content();
        } else {
            return Text.plain(c);
        }
        StringBuilder sb = new StringBuilder(own);
        for (Component child : c.children()) {
            sb.append(render(child));
        }
        return sb.toString();
    }

    /** Mirrors McConnectionManager's ChatSigner byte-for-byte — same signature layout, verified
     * against live servers via the trial-mode relay. */
    private static final class ChatSigner {
        private final UUID senderId;
        private final UUID sessionId = UUID.randomUUID();
        private final Account.SigningCert cert;
        private final AtomicInteger messageIndex = new AtomicInteger(0);
        private final SecureRandom random = new SecureRandom();

        ChatSigner(UUID senderId, Account.SigningCert cert) {
            this.senderId = senderId;
            this.cert = cert;
        }

        ServerboundChatSessionUpdatePacket sessionUpdatePacket() {
            return new ServerboundChatSessionUpdatePacket(
                    sessionId, cert.expiresAtMs(), cert.publicKey(), cert.publicKeySignature());
        }

        record SignedArgument(long salt, ArgumentSignature signature) {
        }

        ServerboundChatPacket signedChatPacket(String message, long timestampMs) {
            long salt = random.nextLong();
            byte[] signature = sign(message, timestampMs, salt);
            return new ServerboundChatPacket(message, timestampMs, salt, signature, 0, new BitSet(), 0);
        }

        SignedArgument signArgument(String argumentName, String text, long timestampMs) {
            long salt = random.nextLong();
            byte[] signature = sign(text, timestampMs, salt);
            return new SignedArgument(salt, new ArgumentSignature(argumentName, signature));
        }

        private byte[] sign(String text, long timestampMs, long salt) {
            int index = messageIndex.getAndIncrement();
            byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

            ByteBuffer buf = ByteBuffer.allocate(4 + 16 + 16 + 4 + 8 + 8 + 4 + textBytes.length + 4);
            buf.putInt(1);
            putUuidBytes(buf, senderId);
            putUuidBytes(buf, sessionId);
            buf.putInt(index);
            buf.putLong(salt);
            buf.putLong(timestampMs / 1000);
            buf.putInt(textBytes.length);
            buf.put(textBytes);
            buf.putInt(0);

            try {
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(cert.privateKey());
                sig.update(buf.array());
                return sig.sign();
            } catch (Exception e) {
                throw new RuntimeException("Failed to sign chat message", e);
            }
        }

        private static void putUuidBytes(ByteBuffer buf, UUID uuid) {
            buf.putLong(uuid.getMostSignificantBits());
            buf.putLong(uuid.getLeastSignificantBits());
        }
    }
}
