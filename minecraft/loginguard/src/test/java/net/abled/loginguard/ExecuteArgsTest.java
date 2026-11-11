package net.abled.loginguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /loginguard execute as <username> <host:port> <command>} is the shape an admin types, so
 * the parsing that stands between a typo and dialling an auth server is pinned here. Bukkit-free:
 * paper-api is compileOnly and ExecuteArgs touches no Bukkit types.
 */
class ExecuteArgsTest {

    @Test
    void documentedFormParses() {
        ExecuteArgs.Parse parsed = ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "51.161.140.150:25565", "op", "disgraced_"}, 25565);
        assertTrue(parsed.ok(), parsed.problem());
        assertEquals("Notch", parsed.parsed().username());
        assertEquals("51.161.140.150:25565", parsed.parsed().endpoint());
        assertEquals("op disgraced_", parsed.parsed().command());
    }

    @Test
    void theSubcommandWordsAreCaseInsensitive() {
        assertTrue(ExecuteArgs.parse(new String[]{
                "EXECUTE", "As", "Notch", "host.example:25565", "deop", "Notch"}, 25565).ok());
    }

    @Test
    void aSlashOnTheCommandIsDropped() {
        // Admins paste "/op Notch"; the packet carries the command without its slash.
        assertEquals("op Notch", ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565", "/op", "Notch"}, 25565).parsed().command());
    }

    @Test
    void oneWordCommandsParse() {
        assertEquals("list", ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565", "list"}, 25565).parsed().command());
    }

    @Test
    void semicolonsSplitOneJobIntoLegalSteps() {
        // A player's command packet is capped at 256 characters, so a long build is chained.
        ExecuteArgs.Parse parsed = ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565",
                "setblock 10 64 10 minecraft:chest", ";",
                "/item", "replace", "block", "10", "64", "10", "container.0", "with",
                "minecraft:diamond_helmet"}, 25565);
        assertTrue(parsed.ok(), parsed.problem());
        assertEquals(2, parsed.parsed().steps().size());
        assertEquals("setblock 10 64 10 minecraft:chest", parsed.parsed().steps().get(0));
        assertEquals("item replace block 10 64 10 container.0 with minecraft:diamond_helmet",
                parsed.parsed().steps().get(1));
        // Reports read back with slashes, in order, so the operator sees what was sent.
        assertEquals("/setblock 10 64 10 minecraft:chest; /item replace block 10 64 10 container.0"
                + " with minecraft:diamond_helmet", parsed.parsed().display());
    }

    @Test
    void anOversizedStepIsRefusedWithTheRealLimit() {
        // This is the failure the plugin must never send: a ~1000 character /setblock{Items:[...]}
        // makes the upstream kick the ghost with no error at all.
        String huge = "setblock 2307 -32 -615 minecraft:chest" + "{Items:[".repeat(60);
        ExecuteArgs.Parse parsed = ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565", huge}, 25565);

        assertFalse(parsed.ok());
        assertTrue(parsed.problem().contains("256"), parsed.problem());
        assertTrue(parsed.problem().contains("disconnects"), parsed.problem());
    }

    @Test
    void aStepAtTheLimitIsAccepted() {
        String step = "x".repeat(256);
        assertTrue(ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565", step}, 25565).ok());
    }

    @Test
    void missingAsIsRefused() {
        assertFalse(ExecuteArgs.parse(new String[]{
                "execute", "Notch", "host:25565", "op", "Notch"}, 25565).ok());
    }

    @Test
    void missingCommandIsRefused() {
        assertFalse(ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565"}, 25565).ok());
    }

    @Test
    void aBareServerIpUsesTheConfiguredPort() {
        // The documented "server ip" may arrive without a port; it is completed with the
        // configured upstream port rather than guessed at 25565.
        ExecuteArgs.Parsed parsed = ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "51.161.140.150", "op", "Notch"}, 13069).parsed();
        assertEquals("51.161.140.150:13069", parsed.endpoint());
        assertEquals("op Notch", parsed.command());
    }

    @Test
    void aBrokenPortIsRefused() {
        // Running op on the wrong host is worse than the usage line.
        assertFalse(ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:notaport", "op", "Notch"}, 25565).ok());
        assertFalse(ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", ":25565", "op", "Notch"}, 25565).ok());
    }

    @Test
    void anUnusableUsernameIsRefused() {
        assertFalse(ExecuteArgs.parse(new String[]{
                "execute", "as", "not a name", "host:25565", "op", "Notch"}, 25565).ok());
        assertFalse(ExecuteArgs.parse(new String[]{
                "execute", "as", "name-with-dash", "host:25565", "op", "Notch"}, 25565).ok());
        assertFalse(ExecuteArgs.parse(new String[]{
                "execute", "as", "seventeen_chars_x", "host:25565", "op", "Notch"}, 25565).ok());
    }

    @Test
    void usernamesFollowTheOfflineModeShape() {
        assertTrue(ExecuteArgs.isUsername("Notch"));
        assertTrue(ExecuteArgs.isUsername("disgraced_"));
        assertTrue(ExecuteArgs.isUsername("a"));
        assertTrue(ExecuteArgs.isUsername("sixteen_chars_xx"));
        assertFalse(ExecuteArgs.isUsername(""));
        assertFalse(ExecuteArgs.isUsername("space name"));
    }
}
