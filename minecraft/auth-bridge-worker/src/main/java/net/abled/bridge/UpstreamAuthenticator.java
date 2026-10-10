package net.abled.bridge;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundShowDialogConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundClearDialogPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the real verification with two interchangeable flows, selected by what the upstream
 * actually shows:
 *
 * <ol>
 *   <li><b>Dialog flow</b> - AuthMe 6.0-style servers that push a Paper Dialog screen with a
 *       text input before/at join. The worker fills the input and clicks the submit action.</li>
 *   <li><b>Chat-command flow</b> - classic AuthMe-style servers whose join message says
 *       "register with /register &lt;password&gt; &lt;password&gt;" or "login with /login
 *       &lt;password&gt;". The worker sends the equivalent {@code ServerboundChatCommandPacket}
 *       and reads the outcome from the reply text.</li>
 * </ol>
 *
 * <p>Flow selection is evidence-based: a dialog packet selects the dialog flow; a chat message
 * matching a command prompt selects the command flow. Anything unexpected times out and fails
 * closed. The command flow's first action with REGISTER reads the register prompt; LOGIN reads
 * the login prompt.
 */
final class UpstreamAuthenticator {

    private final String host;
    private final int port;
    private final long timeoutMs;

    UpstreamAuthenticator(String host, int port, long timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    /** Runs one attempt end to end; caller wipes the request afterwards. */
    Verdict verify(Request request) {
        AtomicReference<Verdict> outcome = new AtomicReference<>(Verdict.UPSTREAM_TIMEOUT);
        CompletableFuture<Verdict> done = new CompletableFuture<>();
        AtomicBoolean responded = new AtomicBoolean(false);
        MinecraftProtocol protocol = new MinecraftProtocol(request.username());
        try {
            Session session = ClientNetworkSessionFactory.factory()
                    .setAddress(host, port)
                    .setProtocol(protocol)
                    .create();
            session.addListener(new SessionAdapter() {
                @Override
                public void connected(ConnectedEvent event) {
                }

                @Override
                public void packetReceived(Session s, Packet packet) {
                    if (packet instanceof ClientboundShowDialogConfigurationPacket show
                            && show.getDialog() != null) {
                        // Evidence: this upstream uses dialogs. Answer once.
                        if (responded.compareAndSet(false, true)) {
                            respondToDialog(s, show.getDialog(), request);
                        }
                    } else if (packet instanceof ClientboundShowDialogGamePacket showGame
                            && showGame.getDialog() != null
                            && showGame.getDialog().isCustom()
                            && showGame.getDialog().custom() instanceof NbtMap nbt) {
                        if (responded.compareAndSet(false, true)) {
                            respondToDialog(s, nbt, request);
                        }
                    } else if (packet instanceof ClientboundSystemChatPacket chat) {
                        String text = PlainText.of(chat.getContent());
                        // If no dialog arrived and the text matches a command prompt, switch to
                        // the command flow and send the matching command line.
                        if (!responded.getAndSet(true)) {
                            if (CommandFlow.respond(s, text, request)) {
                                // command sent; let the reply text classify the outcome
                                return;
                            }
                            // Not a command prompt the worker understands (could be MOTD etc.);
                            // keep listening, other chat may still be the prompt.
                            responded.set(false);
                            return;
                        }
                        Classifier.classify(text, outcome, done);
                    } else if (packet instanceof ClientboundClearDialogPacket) {
                        // dialog closed by the server: either it logged us in or rejected; the
                        // following evidence (chat/disconnect) classifies
                    } else if (packet instanceof ClientboundDisconnectPacket
                            || packet instanceof ClientboundLoginDisconnectPacket) {
                        String reason = packet instanceof ClientboundDisconnectPacket d
                                ? PlainText.of(d.getReason())
                                : packet instanceof ClientboundLoginDisconnectPacket l
                                        ? PlainText.of(l.getReason()) : "";
                        Classifier.onDisconnect(reason, outcome, done);
                    }
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    Classifier.onDisconnect("", outcome, done);
                }
            });
            org.geysermc.mcprotocollib.network.ClientSession clientSession =
                    (org.geysermc.mcprotocollib.network.ClientSession) session;
            clientSession.connect(true);
            done.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            outcome.set(Verdict.UPSTREAM_TIMEOUT);
        } catch (Exception e) {
            outcome.set(Verdict.UPSTREAM_DOWN);
        }
        return outcome.get() == null ? Verdict.UPSTREAM_DOWN : outcome.get();
    }

    /**
     * Fills the dialog's text inputs and clicks the submit action. The first text input
     * receives the password; a second receives the username repeat (register-confirm style).
     * Unknown shapes leave the dialog unanswered - controlled timeout, fail closed.
     */
    private void respondToDialog(Session session, NbtMap dialog, Request request) {
        try {
            NbtMapBuilder payload = NbtMap.builder();
            List<NbtMap> inputs = dialog.getList("inputs", NbtType.COMPOUND, List.of());
            int filled = 0;
            for (NbtMap input : inputs) {
                String type = input.getString("type", "");
                if (type.startsWith("minecraft:text")) {
                    String key = input.getString("key", "input" + filled);
                    payload.putString(key, filled == 0 ? request.passwordAsString().orElse("") : request.username());
                    filled++;
                }
            }
            if (filled == 0) {
                return;
            }
            String actionId = dialog.getString("action", "minecraft:custom/submit");
            session.send(new org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket(
                    net.kyori.adventure.key.Key.key(actionId.replaceFirst("^minecraft:", "")), payload.build()));
        } catch (Exception e) {
            // deny via timeout/down
        }
    }

    private static final class PlainText {
        static String of(net.kyori.adventure.text.Component component) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(component);
        }
    }

    /** evidence-based switch to the plain /register / /login command flow. */
    private static final class CommandFlow {
        /**
         * Returns true if the text matched a known register/login prompt and the equivalent
         * command was sent.
         */
        static boolean respond(Session session, String text, Request request) {
            String lower = text.toLowerCase();
            if (lower.contains("register")) {
                sendCommand(session, request.action() == Request.Action.REGISTER
                        ? "/register " + request.passwordAsString().orElse("") + " " + request.passwordAsString().orElse("")
                        : "/login " + request.passwordAsString().orElse(""));
                return true;
            }
            if (lower.contains("log in") || lower.contains("login")) {
                sendCommand(session, "/login " + request.passwordAsString().orElse(""));
                return true;
            }
            return false;
        }

        private static void sendCommand(Session session, String command) {
            // ServerboundChatCommandPacket carries the command without the leading slash;
            // the 26.3 API takes just the command string and timestamps/signs internally.
            session.send(new ServerboundChatCommandPacket(command.substring(1)));
        }
    }

    /** Shared classifier. */
    private static final class Classifier {
        static void classify(String text, AtomicReference<Verdict> outcome, CompletableFuture<Verdict> done) {
            String lower = text.toLowerCase();
            if (lower.contains("logged in") || lower.contains("successfully") || lower.contains("welcome")
                    || lower.contains("registered successfully")) {
                outcome.set(Verdict.SUCCESS);
                done.complete(outcome.get());
            } else if (lower.contains("wrong password") || lower.contains("incorrect")
                    || lower.contains("invalid password") || lower.contains("already registered")) {
                outcome.set(Verdict.WRONG_PASSWORD);
                done.complete(outcome.get());
            } else if (lower.contains("unknown user") || lower.contains("user not registered")
                    || lower.contains("not registered")) {
                outcome.set(Verdict.NO_ACCOUNT);
                done.complete(outcome.get());
            }
        }

        static void onDisconnect(String reason, AtomicReference<Verdict> outcome, CompletableFuture<Verdict> done) {
            classify(reason, outcome, done);
            if (outcome.get() == Verdict.UPSTREAM_TIMEOUT) {
                outcome.set(Verdict.UPSTREAM_DOWN);
            }
            done.complete(outcome.get());
        }
    }
}
