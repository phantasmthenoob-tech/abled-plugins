package net.abled.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.network.packet.PacketRegistry;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginCompressionPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundLoginAcknowledgedPacket;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login-success packet is the one place where the upstream's wire format and the bundled
 * protocol library can legitimately disagree: releases before 26.3 end the packet after the
 * profile, 26.3 appends a session UUID. Reading the newer shape from an older upstream used to
 * kill the session with a Netty decoder error, which the plugin then reported as UPSTREAM_DOWN
 * for an account whose password was never even tried.
 *
 * <p>The fake upstream here speaks the real framing (uncompressed SetCompression, then a
 * compression-enabled LoginSuccess) so a regression shows up as "the client never acknowledged
 * the login", not as an assertion about a private method.
 */
class LoginSuccessFormatTest {

    private static final int THRESHOLD = 256;
    private static final PacketRegistry LOGIN =
            MinecraftCodec.CODEC.getCodec(ProtocolState.LOGIN);

    @Test
    void acceptsLoginSuccessWithoutTheTrailingSessionId() throws Exception {
        assertLoginCompletes(false);
    }

    @Test
    void acceptsLoginSuccessWithTheTrailingSessionId() throws Exception {
        assertLoginCompletes(true);
    }

    private void assertLoginCompletes(boolean withSessionId) throws Exception {
        List<String> observed = new CopyOnWriteArrayList<>();
        try (FakeUpstream upstream = new FakeUpstream(withSessionId)) {
            UpstreamAuthenticator authenticator = new UpstreamAuthenticator(
                    List.of(new UpstreamAuthenticator.Endpoint("127.0.0.1", upstream.port())),
                    2000, ConnectRoute.DIRECT, observed::add);
            Request request = new Request(UUID.randomUUID(), "ImNotAllocate",
                    "hunter2".toCharArray(), Request.Action.LOGIN);

            UpstreamAuthenticator.Outcome outcome = authenticator.verify(request);

            assertTrue(upstream.loginAcknowledged(),
                    "the client never sent LoginAcknowledged; the upstream log was " + observed);
            // The fake upstream never shows a login prompt, so the attempt ends on its budget
            // rather than on a decode failure.
            assertNotEquals(Verdict.UPSTREAM_DOWN, outcome.verdict(),
                    "the login-success packet was rejected: " + observed);
        }
    }

    /**
     * Minimal login-phase server: handshake, SetCompression, LoginSuccess, then watch for the
     * client's acknowledgement. Everything after the acknowledgement is left unanswered on
     * purpose, so the attempt runs into the authenticator's timeout.
     */
    private static final class FakeUpstream implements AutoCloseable {

        private final ServerSocket server;
        private final boolean withSessionId;
        private final AtomicBoolean loginAcknowledged = new AtomicBoolean(false);
        private final Thread thread;

        FakeUpstream(boolean withSessionId) throws IOException {
            this.withSessionId = withSessionId;
            this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve, "fake-upstream");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        boolean loginAcknowledged() throws InterruptedException {
            thread.join(3000);
            return loginAcknowledged.get();
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(4000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // The client's handshake and login start are not interesting here.
                writeFrame(out, packet(LOGIN.getClientboundId(ClientboundLoginCompressionPacket.class),
                        body -> MinecraftTypes.writeVarInt(body, THRESHOLD)), false);

                UUID id = UUID.nameUUIDFromBytes("ImNotAllocate".getBytes());
                writeFrame(out, packet(LOGIN.getClientboundId(ClientboundLoginFinishedPacket.class), body -> {
                    MinecraftTypes.writeUUID(body, id);
                    MinecraftTypes.writeString(body, "ImNotAllocate");
                    MinecraftTypes.writeVarInt(body, 0); // no profile properties
                    if (withSessionId) {
                        MinecraftTypes.writeUUID(body, new UUID(0L, 0L));
                    }
                }), true);

                int acknowledged = LOGIN.getServerboundId(ServerboundLoginAcknowledgedPacket.class);
                while (true) {
                    byte[] frame = readFrame(in);
                    if (frame == null) {
                        return;
                    }
                    // Compression is on from SetCompression onwards; dataLength 0 = not deflated.
                    int length = readVarInt(frame, 0);
                    int offset = varIntSize(length);
                    if (length != 0) {
                        continue;
                    }
                    if (readVarInt(frame, offset) == acknowledged) {
                        loginAcknowledged.set(true);
                        // Keep the connection open: closing it here would look like an
                        // upstream that dropped mid-flow, which is a different verdict.
                    }
                }
            } catch (IOException ignored) {
                // the client gave up (timeout) - the assertions report what was observed
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private interface Body {
        void write(ByteBuf buf);
    }

    private static byte[] packet(int id, Body body) {
        ByteBuf buf = Unpooled.buffer();
        try {
            MinecraftTypes.writeVarInt(buf, id);
            body.write(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static void writeFrame(OutputStream out, byte[] payload, boolean compressed) throws IOException {
        if (!compressed) {
            out.write(varInt(payload.length));
            out.write(payload);
        } else {
            // A payload below the threshold travels uncompressed behind a zero data length.
            byte[] dataLength = varInt(0);
            out.write(varInt(dataLength.length + payload.length));
            out.write(dataLength);
            out.write(payload);
        }
        out.flush();
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0) {
            return null;
        }
        byte[] frame = in.readNBytes(length);
        return frame.length == length ? frame : null;
    }

    private static byte[] varInt(int value) {
        ByteBuf buf = Unpooled.buffer();
        try {
            MinecraftTypes.writeVarInt(buf, value);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static int varIntSize(int value) {
        return varInt(value).length;
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int read = in.read();
            if (read < 0) {
                return -1;
            }
            value |= (read & 0x7F) << (7 * i);
            if ((read & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }

    private static int readVarInt(byte[] bytes, int offset) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int read = bytes[offset + i] & 0xFF;
            value |= (read & 0x7F) << (7 * i);
            if ((read & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("not a varint");
    }
}
