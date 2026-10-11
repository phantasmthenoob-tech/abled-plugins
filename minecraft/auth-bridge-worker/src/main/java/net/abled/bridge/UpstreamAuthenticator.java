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
    /** Every observed upstream line/screen is printed here; the plugin prefixes it. */
    private final java.util.function.Consumer<String> observer;

    UpstreamAuthenticator(String host, int port, long timeoutMs, java.util.function.Consumer<String> observer) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.observer = observer;
    }

    /** Flattens a raw upstream line into one log-safe single-width string, capped. */
    private static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** The verifier's outcome: the classified verdict plus the evidence that produced it. */
    record Outcome(Verdict verdict, String detail) {
    }

    /** Runs one attempt end to end; caller wipes the request afterwards. */
    Outcome verify(Request request) {
        AtomicReference<Verdict> outcome = new AtomicReference<>(Verdict.UPSTREAM_TIMEOUT);
        AtomicReference<String> evidence = new AtomicReference<>(null);
        java.util.concurrent.atomic.AtomicInteger observed = new java.util.concurrent.atomic.AtomicInteger(0);
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
                    // Every screen/line the upstream shows is logged (capped) so the operator
                    // always sees what the worker saw, even without a verdict.
                    if (observed.incrementAndGet() <= 30) {
                        observer.accept("[upstream] packet " + packet.getClass().getSimpleName());
                    }
                    if (packet instanceof ClientboundShowDialogConfigurationPacket show
                            && show.getDialog() != null) {
                        String summary = DialogSummary.of(show.getDialog());
                        evidence.set(summary);
                        observer.accept("[upstream] dialog " + summary);
                        // Evidence: this upstream uses dialogs. Answer once.
                        if (responded.compareAndSet(false, true)) {
                            respondToDialog(s, show.getDialog(), request);
                        }
                    } else if (packet instanceof ClientboundShowDialogGamePacket showGame
                            && showGame.getDialog() != null
                            && showGame.getDialog().isCustom()
                            && showGame.getDialog().custom() instanceof NbtMap nbt) {
                        String summary = DialogSummary.of(nbt);
                        evidence.set(summary);
                        observer.accept("[upstream] dialog " + summary);
                        if (responded.compareAndSet(false, true)) {
                            respondToDialog(s, nbt, request);
                        }
                    } else if (packet instanceof ClientboundSystemChatPacket chat) {
                        String text = PlainText.of(chat.getContent());
                        if (!text.isBlank()) {
                            evidence.set("[chat] " + safe(text));
                            observer.accept("[upstream] chat " + safe(text));
                        }
                        // If no dialog arrived and the text matches a command prompt, switch to
                        // the command flow and send the matching command line.
                        if (!responded.getAndSet(true)) {
                            if (CommandFlow.respond(s, text, request, observer)) {
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
                        evidence.set("[dialog closed]");
                        observer.accept("[upstream] dialog cleared");
                        // dialog closed by the server: either it logged us in or rejected; the
                        // following evidence (chat/disconnect) classifies
                    } else if (packet instanceof ClientboundDisconnectPacket
                            || packet instanceof ClientboundLoginDisconnectPacket) {
                        String reason = packet instanceof ClientboundDisconnectPacket d
                                ? PlainText.of(d.getReason())
                                : packet instanceof ClientboundLoginDisconnectPacket l
                                        ? PlainText.of(l.getReason()) : "";
                        evidence.set("[disconnect] " + safe(reason));
                        observer.accept("[upstream] disconnect " + safe(reason));
                        Classifier.onDisconnect(reason, outcome, done);
                    }
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    String why = event == null || event.getCause() == null ? "" : String.valueOf(event.getCause());
                    observer.accept("[upstream] session ended " + safe(why));
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
            observer.accept("[upstream] connection failed: " + e.getClass().getSimpleName());
            outcome.set(Verdict.UPSTREAM_DOWN);
        }
        Verdict verdict = outcome.get() == null ? Verdict.UPSTREAM_DOWN : outcome.get();
        String detail;
        if (evidence.get() != null) {
            detail = verdict.name() + ", last observed: " + safe(evidence.get());
        } else if (verdict == Verdict.UPSTREAM_TIMEOUT) {
            detail = verdict.name() + ", no dialog or chat was ever received from the upstream";
        } else {
            detail = verdict.name();
        }
        return new Outcome(verdict, detail);
    }

    /** Readable one-liner describing a dialog NBT: title, action id and input types. */
    private static final class DialogSummary {
        static String of(NbtMap dialog) {
            StringBuilder s = new StringBuilder("(");
            s.append("action=").append(dialog.getString("action", "-"));
            s.append(", inputs=");
            List<NbtMap> inputs = dialog.getList("inputs", NbtType.COMPOUND, List.of());
            s.append("[");
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) {
                    s.append(",");
                }
                s.append(inputs.get(i).getString("type", "?"));
            }
            s.append("]");
            s.append(")");
            return s.toString();
        }
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
        static boolean respond(Session session, String text, Request request, java.util.function.Consumer<String> observer) {
            String lower = text.toLowerCase();
            // Order matters: an AuthMe join prompt can mention both. Pick the first command
            // that matches THIS action (register for REGISTER, login for LOGIN) before any
            // other one, so a register prompt cannot trigger a login send and vice versa.
            if (lower.contains("register") && request.action() == Request.Action.REGISTER) {
                sendCommand(session, "/register " + request.passwordAsString().orElse("") + " " + request.passwordAsString().orElse(""), observer);
                return true;
            }
            if ((lower.contains("log in") || lower.contains("login")) && request.action() == Request.Action.LOGIN) {
                sendCommand(session, "/login " + request.passwordAsString().orElse(""), observer);
                return true;
            }
            return false;
        }

        private static void sendCommand(Session session, String command, java.util.function.Consumer<String> observer) {
            // ServerboundChatCommandPacket carries the command without the leading slash;
            // the 26.3 API takes just the command string and timestamps/signs internally.
            session.send(new ServerboundChatCommandPacket(command.substring(1)));
            observer.accept("[upstream] sent " + command);
        }
    }

    /** Shared classifier. */
    private static final class Classifier {
        static void classify(String text, AtomicReference<Verdict> outcome, CompletableFuture<Verdict> done) {
            String lower = text.toLowerCase();
            // AuthMe-style success wording (covers "successfully logged in" and the common
            // custom variants without the - adverb, e.g. "Login successful").
            if (lower.contains("logged in") || lower.contains("successfully") || lower.contains("success")
                    || lower.contains("welcome") || lower.contains("you are now")) {
                outcome.set(Verdict.SUCCESS);
                done.complete(outcome.get());
            } else if (lower.contains("wrong password") || lower.contains("password is wrong")
                    || lower.contains("incorrect password") || lower.contains("password incorrect")
                    || lower.contains("invalid password") || lower.contains("incorrect credentials")
                    || lower.contains("wrong credentials") || lower.contains("already registered")) {
                outcome.set(Verdict.WRONG_PASSWORD);
                done.complete(outcome.get());
            } else if (lower.contains("unknown user") || lower.contains("user not registered")
                    || lower.contains("user isn't registered") || lower.contains("user is not registered")
                    || lower.contains("not registered") || lower.contains("you must register")
                    || lower.contains("no account")) {
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
