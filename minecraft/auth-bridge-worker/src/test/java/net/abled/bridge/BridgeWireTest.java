package net.abled.bridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control-socket wire format is where a silent mistake costs the most: a wrong split truncates
 * an operator command or loses a password. Both request shapes are pinned here.
 */
class BridgeWireTest {

    @Test
    void verifyLineKeepsThePasswordWhole() {
        UUID id = UUID.randomUUID();
        Request request = BridgeServer.parseLine("VERIFY " + id + " LOGIN Notch swordfish1");

        assertEquals(id, request.requestId());
        assertEquals(Request.Action.LOGIN, request.action());
        assertEquals("Notch", request.username());
        assertEquals("swordfish1", request.passwordAsString().orElseThrow());
        assertNull(request.command());
        assertNull(request.target());
    }

    @Test
    void execLineKeepsACamelCaseAction() {
        UUID id = UUID.randomUUID();
        Request request = BridgeServer.parseLine("VERIFY " + id + " REGISTER Notch swordfish1");

        assertEquals(Request.Action.REGISTER, request.action());
    }

    @Test
    void execLineCarriesTargetPasswordAndTheWholeCommand() {
        UUID id = UUID.randomUUID();
        Request request = BridgeServer.parseLine(
                "EXEC " + id + " 51.161.140.150:25565 Notch swordfish1 op disgraced_");

        assertEquals(id, request.requestId());
        assertEquals(Request.Action.EXECUTE, request.action());
        assertEquals("51.161.140.150:25565", request.target());
        assertEquals("Notch", request.username());
        assertEquals("swordfish1", request.passwordAsString().orElseThrow());
        assertEquals("op disgraced_", request.command());
        // An execute logs in first: it must take the login prompt, never the register one.
        assertTrue(request.needsLogin());
    }

    @Test
    void execLineNeedsEveryField() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> BridgeServer.parseLine("EXEC " + id + " host:25565 Notch swordfish1"));
        assertThrows(IllegalArgumentException.class,
                () -> BridgeServer.parseLine("EXEC " + id + " host:25565 Notch  op Notch"));
        assertThrows(IllegalArgumentException.class,
                () -> BridgeServer.parseLine("EXEC " + id));
    }

    @Test
    void unknownRequestTypesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BridgeServer.parseLine("DELETE " + UUID.randomUUID() + " Notch"));
    }

    @Test
    void aWholeLineCommandSurvivesForTheExecutePath() {
        UUID id = UUID.randomUUID();
        Request request = BridgeServer.parseLine("EXEC " + id + " host:25565 Notch pw12345 "
                + "minecraft:give @s diamond 64");
        assertEquals("minecraft:give @s diamond 64", request.command());
        assertEquals("minecraft:give @s diamond 64", request.commandAsString().orElseThrow());
    }
}
