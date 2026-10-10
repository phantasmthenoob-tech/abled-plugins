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
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Drives the real verification: connects to the upstream offline-mode server with
 * {@link Request#username()}, receives AuthMe's pre-join login {@code Dialog}, and clicks the
 * submit action carrying the typed password.
 *
 * <p>The dialog arrives as {@link ClientboundShowDialogConfigurationPacket} during
 * configuration; its NBT carries the input field ids and the action id. A response is
 * {@code ServerboundCustomClickActionPacket(id, payload)} where the payload maps each input's
 * id to its typed string. Everything upstream-specific lives in this class only.
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
        MinecraftProtocol protocol = new MinecraftProtocol(request.username());
        List<Runnable> disposables = new ArrayList<>();
        try {
            Session session = ClientNetworkSessionFactory.factory()
                    .setAddress(host, port)
                    .setProtocol(protocol)
                    .create();
            session.addListener(new SessionAdapter() {
                @Override
                public void connected(ConnectedEvent event) {
                    // configuration begins immediately; nothing to send until the dialog arrives
                }

                @Override
                public void packetReceived(Session session1, Packet packet) {
                    if (packet instanceof ClientboundShowDialogConfigurationPacket showDialog
                            && showDialog.getDialog() != null) {
                        respondToDialog(session1, showDialog.getDialog(), request, outcome);
                    } else if (packet instanceof ClientboundShowDialogGamePacket showDialogGame
                            && showDialogGame.getDialog() != null) {
                        // Holder may wrap an inline NbtMap (id-based registry dialogs cannot
                        // be answered inline and fall through to the timeout path)
                        if (showDialogGame.getDialog().isCustom()
                                && showDialogGame.getDialog().custom() instanceof NbtMap nbt) {
                            respondToDialog(session1, nbt, request, outcome);
                        }
                    } else if (packet instanceof ClientboundSystemChatPacket chat) {
                        String text = PlainText.of(chat.getContent());
                        Classifier.classify(text, outcome, done);
                    } else if (packet instanceof ClientboundClearDialogPacket) {
                        // dialog closed: outcome hinges on what follows; timeout catches limbo
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
            org.geysermc.mcprotocollib.network.ClientSession clientSession = (org.geysermc.mcprotocollib.network.ClientSession) session;
            clientSession.connect(true);
            done.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            outcome.set(Verdict.UPSTREAM_TIMEOUT);
        } catch (Exception e) {
            outcome.set(Verdict.UPSTREAM_DOWN);
        } finally {
            disposables.forEach(Runnable::run);
        }
        return outcome.get() == null ? Verdict.UPSTREAM_DOWN : outcome.get();
    }

    /**
     * Pulls the action id and input field ids from the dialog NBT and answers with a custom
     * click carrying the username/password input values.
     */
    private void respondToDialog(Session session, NbtMap dialog, Request request,
                                 AtomicReference<Verdict> outcome) {
        // Dialog NBT shape (Minecraft "minecraft:custom" dialog): inputs list of
        // {type, key/name, ...}, body, and action button with action id for custom action
        // dialogs. Field ids are not universally named, so this walks the structure and
        // fills the first text input with the password, second (if any) with the username
        // confirmation. An unexpected shape leaves the dialog unanswered and the overall
        // attempt times out - fail closed.
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
                // No text input understood; leave dialog unanswered -> controlled timeout.
                return;
            }
            String actionId = dialog.getString("action", "minecraft:custom/submit");
            session.send(new org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket(
                    net.kyori.adventure.key.Key.key(actionId.replaceFirst("^minecraft:", "")), payload.build()));
            outcome.set(Verdict.UPSTREAM_TIMEOUT); // stays until evidence arrives
        } catch (Exception e) {
            // Unexpected NBT shape or send failure: deny via timeout/down and stop.
        }
    }

    /** Freelance helpers shared across the listeners above; kept trivial on purpose. */
    private static final class PlainText {
        static String of(net.kyori.adventure.text.Component component) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(component);
        }
    }

    /** Text-based classification, mirrored for disconnect reasons. */
    private static final class Classifier {
        static void classify(String text, AtomicReference<Verdict> outcome, CompletableFuture<Verdict> done) {
            String lower = text.toLowerCase();
            if (lower.contains("logged in") || lower.contains("successfully") || lower.contains("welcome")) {
                outcome.set(Verdict.SUCCESS);
                done.complete(outcome.get());
            } else if (lower.contains("wrong password") || lower.contains("incorrect")
                    || lower.contains("invalid password")) {
                outcome.set(Verdict.WRONG_PASSWORD);
                done.complete(outcome.get());
            } else if (lower.contains("register") || lower.contains("unknown user")
                    || lower.contains("user not registered") || lower.contains("not registered")) {
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
