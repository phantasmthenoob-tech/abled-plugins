package net.abled.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.network.codec.PacketDefinition;
import org.geysermc.mcprotocollib.network.codec.PacketSerializer;
import org.geysermc.mcprotocollib.network.packet.PacketRegistry;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacketSerializer;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

/**
 * Teaches the bundled 26.3 protocol to read the login-success packet of an older upstream.
 *
 * <p>26.3 appends a session UUID after the game profile; releases before it end the packet with
 * the property list. A 26.3 decoder reading an older upstream therefore consumes the whole frame
 * as profile and then fails to read 8 more bytes for the UUID, which kills the session before
 * the authentication prompt is ever shown and surfaces as {@code UPSTREAM_DOWN} for an account
 * whose password was never tried. This is the normal case for a server that still runs 26.2
 * behind a proxy or ViaVersion, which is exactly what the worker is pointed at here.
 *
 * <p>The fix is confined to the one packet: the login registry entry is replaced by a parser
 * that first tries the 26.3 shape and, only if it runs out of bytes, retries with a zero session
 * UUID appended. Every other packet keeps the stock definition. The registry is process-wide
 * (the worker only ever speaks one protocol version), so installation is once per JVM.
 */
final class LoginSuccessCompat {

    private static final int SESSION_ID_BYTES = 16;
    private static boolean installed;

    private LoginSuccessCompat() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        PacketRegistry login = MinecraftCodec.CODEC.getCodec(ProtocolState.LOGIN);
        int id = login.getClientboundId(ClientboundLoginFinishedPacket.class);
        PacketSerializer<ClientboundLoginFinishedPacket> serializer =
                new MinecraftPacketSerializer<>(LoginSuccessCompat::parse);
        login.registerClientbound(new PacketDefinition<>(id, ClientboundLoginFinishedPacket.class, serializer));
        installed = true;
    }

    private static ClientboundLoginFinishedPacket parse(ByteBuf in) {
        int start = in.readerIndex();
        try {
            return new ClientboundLoginFinishedPacket(in);
        } catch (IndexOutOfBoundsException olderUpstream) {
            // Not a malformed packet: the session UUID simply is not on the wire.
            in.readerIndex(start);
            ByteBuf padded = Unpooled.buffer(in.readableBytes() + SESSION_ID_BYTES);
            try {
                padded.writeBytes(in);
                padded.writeZero(SESSION_ID_BYTES);
                return new ClientboundLoginFinishedPacket(padded);
            } finally {
                padded.release();
            }
        }
    }
}
