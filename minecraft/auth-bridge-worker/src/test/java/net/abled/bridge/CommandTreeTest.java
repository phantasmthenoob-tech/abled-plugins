package net.abled.bridge;

import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upstream builds the command tree per player, so this is the one place that can tell
 * "the command did nothing" apart from "this account is not op on that server".
 */
class CommandTreeTest {

    private static CommandNode node(String name, int... children) {
        return new CommandNode(CommandType.LITERAL, true, false, children, OptionalInt.empty(),
                name, null, null, null);
    }

    private static CommandNode root(int... children) {
        return new CommandNode(CommandType.ROOT, false, false, children, OptionalInt.empty(),
                "", null, null, null);
    }

    private static CommandTree tree(CommandNode[] nodes, int first) {
        return CommandTree.of(new ClientboundCommandsPacket(nodes, first));
    }

    /** A player who is op: /setblock and /item are in the tree, /op is not (level 3). */
    private static CommandTree op() {
        return tree(new CommandNode[]{root(1, 2, 3), node("setblock"), node("item"),
                node("help")}, 0);
    }

    @Test
    void readsTheTopLevelOfTheTree() {
        CommandTree tree = op();
        assertTrue(tree.offers("setblock"));
        assertTrue(tree.offers("SETBLOCK"));
        assertTrue(tree.offers("item"));
        assertFalse(tree.offers("op"));
        assertEquals(3, tree.size());
    }

    @Test
    void namesOnlyTheVerbsTheAccountCannotRun() {
        CommandTree tree = op();
        List<String> missing = tree.missingVerbs(List.of(
                "setblock 1 2 3 minecraft:chest",
                "/item replace block 1 2 3 container.0 with minecraft:stone",
                "op SouthWe"));
        assertEquals(List.of("op"), missing);
    }

    @Test
    void aPlainPlayerMissesEveryVanillaAdminVerb() {
        // What a non-op really receives: only the level-0 commands.
        CommandTree player = tree(new CommandNode[]{root(1), node("help")}, 0);
        List<String> missing = player.missingVerbs(List.of(
                "setblock 2307 -32 -615 minecraft:chest",
                "item replace block 2307 -32 -615 container.0 with minecraft:stone"));
        assertEquals(List.of("setblock", "item"), missing);
    }

    @Test
    void aTreeThatNeverArrivedConcludesNothing() {
        // An empty node array means the packet was not understood, not that nothing is allowed:
        // claiming "not op" from it would be a guess.
        CommandTree empty = tree(new CommandNode[0], 0);
        assertTrue(empty.isEmpty());
        assertTrue(empty.missingVerbs(List.of("setblock 1 2 3 minecraft:chest")).isEmpty());
    }

    @Test
    void ignoresChildrenThatPointOutsideTheNodeArray() {
        CommandTree tree = tree(new CommandNode[]{root(1, 99), node("setblock")}, 0);
        assertEquals(1, tree.size());
        assertTrue(tree.offers("setblock"));
    }

    @Test
    void verbOfStripsSlashesAndArguments() {
        assertEquals("setblock", CommandTree.verbOf("/setblock 2307 -32 -615 minecraft:chest"));
        assertEquals("item", CommandTree.verbOf("  item   replace block 1 2 3 container.0"));
        assertEquals("op", CommandTree.verbOf("op"));
        assertEquals("", CommandTree.verbOf(null));
    }
}
