package net.abled.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A long operator job arrives as {@code cmd1 ; cmd2}; splitting it is what keeps every packet
 * inside the 256-character command limit, so the split is pinned here.
 */
class CommandStepsTest {

    @Test
    void semicolonsSplitAndTrim() {
        Request request = new Request(java.util.UUID.randomUUID(), "Notch", "pw".toCharArray(),
                Request.Action.EXECUTE, "host:25565", "op Notch ; deop Griefer");

        assertEquals(List.of("op Notch", "deop Griefer"), request.commandSteps());
    }

    @Test
    void aLeadingSlashIsDroppedFromEveryStep() {
        Request request = new Request(java.util.UUID.randomUUID(), "Notch", "pw".toCharArray(),
                Request.Action.EXECUTE, "host:25565", "/op Notch;/deop Griefer");

        assertEquals(List.of("op Notch", "deop Griefer"), request.commandSteps());
    }

    @Test
    void oneCommandIsOneStep() {
        Request request = new Request(java.util.UUID.randomUUID(), "Notch", "pw".toCharArray(),
                Request.Action.EXECUTE, "host:25565", "setblock 1 2 3 stone");

        assertEquals(List.of("setblock 1 2 3 stone"), request.commandSteps());
    }

    @Test
    void aVerifyRequestHasNoSteps() {
        Request request = new Request(java.util.UUID.randomUUID(), "Notch", "pw".toCharArray(),
                Request.Action.LOGIN);

        assertTrue(request.commandSteps().isEmpty());
    }
}
