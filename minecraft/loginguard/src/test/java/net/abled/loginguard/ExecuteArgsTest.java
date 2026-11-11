package net.abled.loginguard;

import org.junit.jupiter.api.Test;

import java.util.Optional;

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
        Optional<ExecuteArgs.Parsed> parsed = ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "51.161.140.150:25565", "op", "disgraced_"}, 25565);
        assertTrue(parsed.isPresent());
        assertEquals("Notch", parsed.get().username());
        assertEquals("51.161.140.150:25565", parsed.get().endpoint());
        assertEquals("op disgraced_", parsed.get().command());
    }

    @Test
    void theSubcommandWordsAreCaseInsensitive() {
        assertTrue(ExecuteArgs.parse(new String[]{
                "EXECUTE", "As", "Notch", "host.example:25565", "deop", "Notch"}, 25565).isPresent());
    }

    @Test
    void aSlashOnTheCommandIsDropped() {
        // Admins paste "/op Notch"; the packet carries the command without its slash.
        assertEquals("op Notch", ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565", "/op", "Notch"}, 25565).orElseThrow().command());
    }

    @Test
    void oneWordCommandsParse() {
        assertEquals("list", ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565", "list"}, 25565).orElseThrow().command());
    }

    @Test
    void missingAsIsRejected() {
        assertTrue(ExecuteArgs.parse(new String[]{
                "execute", "Notch", "host:25565", "op", "Notch"}, 25565).isEmpty());
    }

    @Test
    void missingCommandIsRejected() {
        assertTrue(ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:25565"}, 25565).isEmpty());
    }

    @Test
    void aBareServerIpUsesTheConfiguredPort() {
        // The documented "server ip" may arrive without a port; it is completed with the
        // configured upstream port rather than guessed at 25565.
        ExecuteArgs.Parsed parsed = ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "51.161.140.150", "op", "Notch"}, 13069).orElseThrow();
        assertEquals("51.161.140.150:13069", parsed.endpoint());
        assertEquals("op Notch", parsed.command());
    }

    @Test
    void aBrokenPortIsRejected() {
        // Running op on the wrong host is worse than the usage line.
        assertTrue(ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", "host:notaport", "op", "Notch"}, 25565).isEmpty());
        assertTrue(ExecuteArgs.parse(new String[]{
                "execute", "as", "Notch", ":25565", "op", "Notch"}, 25565).isEmpty());
    }

    @Test
    void anUnusableUsernameIsRejected() {
        assertTrue(ExecuteArgs.parse(new String[]{
                "execute", "as", "not a name", "host:25565", "op", "Notch"}, 25565).isEmpty());
        assertTrue(ExecuteArgs.parse(new String[]{
                "execute", "as", "name-with-dash", "host:25565", "op", "Notch"}, 25565).isEmpty());
        assertTrue(ExecuteArgs.parse(new String[]{
                "execute", "as", "seventeen_chars_x", "host:25565", "op", "Notch"}, 25565).isEmpty());
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
