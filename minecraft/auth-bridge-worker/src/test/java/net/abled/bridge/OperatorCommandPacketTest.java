package net.abled.bridge;

import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The operator command travels to the upstream as one {@code ServerboundChatCommandPacket} string.
 * If anything in that codec dropped everything after the verb, {@code /setblock 100 64 100 stone}
 * would reach the server as {@code /setblock} - so the round trip is pinned here rather than
 * assumed from the constructor signature.
 */
class OperatorCommandPacketTest {

    @Test
    void coordinatesAndBlockSurviveTheCodec() {
        String line = "setblock 100 64 100 minecraft:stone";
        ServerboundChatCommandPacket sent = new ServerboundChatCommandPacket(line);

        io.netty.buffer.ByteBuf buf = Unpooled.buffer();
        sent.serialize(buf);
        ServerboundChatCommandPacket read = new ServerboundChatCommandPacket(buf);

        assertEquals(line, read.getCommand());
    }

    @Test
    void selectorArgumentsAndRelativeCoordinatesSurviveTheCodec() {
        String line = "execute as @a at @s run setblock ~ ~1 ~ stone";
        io.netty.buffer.ByteBuf buf = Unpooled.buffer();
        new ServerboundChatCommandPacket(line).serialize(buf);

        assertEquals(line, new ServerboundChatCommandPacket(buf).getCommand());
    }
}
