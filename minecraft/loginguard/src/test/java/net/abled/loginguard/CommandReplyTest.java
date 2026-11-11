package net.abled.loginguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upstream's refusal, taken verbatim from a real run: the command reached the ghost's own
 * session and the server answered with translation keys, which the report used to call a success.
 */
class CommandReplyTest {

    /** Exactly what the bridge recorded for {@code /setblock 2307 -32 -615 minecraft:chest}. */
    private static final String OBSERVED = "the upstream refused it: command.unknown.command setblock"
            + " 2307 -32 -615 minecraft:chestcommand.context.here";

    @Test
    void recognisesTheKeyTextOfAnUnknownCommand() {
        assertTrue(CommandReply.looksRefused(OBSERVED));
    }

    @Test
    void recognisesKeyTextWithoutThePrefix() {
        assertTrue(CommandReply.looksRefused(
                "command.unknown.command item replace block 1 2 3 container.0 with minecraft:stone"
                        + "command.context.here"));
    }

    @Test
    void recognisesCommandFailuresAndEnglishWordings() {
        assertTrue(CommandReply.looksRefused("commands.setblock.failed"));
        assertTrue(CommandReply.looksRefused("You do not have permission to use this command"));
        assertTrue(CommandReply.looksRefused("That position is not loaded"));
        assertTrue(CommandReply.looksRefused("You are not allowed to use this command"));
    }

    @Test
    void aRealAnswerIsNotARefusal() {
        assertFalse(CommandReply.looksRefused("login confirmed; the upstream answered: Done"));
        assertFalse(CommandReply.looksRefused("Changed the block at 2307, -32, -615"));
        assertFalse(CommandReply.looksRefused(""));
        assertFalse(CommandReply.looksRefused(null));
    }

    @Test
    void aKickIsRecognisedSeparately() {
        assertTrue(CommandReply.looksDropped("sent /setblock but the server disconnected the ghost"
                + " (You used a command too fast!) - treat the commands as not applied"));
        assertFalse(CommandReply.looksDropped(OBSERVED));
    }

    @Test
    void theTreeGapIsTheProofOfMissingPermission() {
        assertTrue(CommandReply.namesMissingVerb(OBSERVED
                + " | the upstream's command tree for this account has no /setblock"));
        assertFalse(CommandReply.namesMissingVerb(OBSERVED));
    }

    @Test
    void refusalNoticeIsYellowAndKeepsTheDetail() {
        assertTrue(CommandReply.refusedNotice("SouthWe on host", OBSERVED).startsWith("\u00A7e"));
        assertTrue(CommandReply.refusedNotice("SouthWe on host", OBSERVED).endsWith(OBSERVED));
    }
}
