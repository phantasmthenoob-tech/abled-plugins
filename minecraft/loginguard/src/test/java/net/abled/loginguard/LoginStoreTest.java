package net.abled.loginguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /loginguard execute as} is gated on "this player has logged in here before", which means
 * the record lookup is the security-relevant part of the command. It is pinned here.
 */
class LoginStoreTest {

    private static Path record(Path folder, String username, String password) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve(UUID.randomUUID() + ".login");
        Files.writeString(file, "username: " + username + "\npassword: " + password + "\n",
                StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void findsARecordInTheUpstreamFolder(@TempDir Path root) throws IOException {
        record(root.resolve("51.161.140.150"), "Notch", "swordfish1");

        Optional<LoginStore.Stored> found = LoginStore.findByUsername(root, "Notch");

        assertTrue(found.isPresent());
        assertEquals("Notch", found.get().username());
        assertEquals("swordfish1", found.get().password());
        assertEquals("51.161.140.150", found.get().upstreamFolder());
    }

    @Test
    void findsAFlatLocalOnlyRecord(@TempDir Path root) throws IOException {
        record(root, "Notch", "swordfish1");

        Optional<LoginStore.Stored> found = LoginStore.findByUsername(root, "Notch");

        assertTrue(found.isPresent());
        assertEquals("", found.get().upstreamFolder());
    }

    @Test
    void usernameMatchIgnoresCase(@TempDir Path root) throws IOException {
        record(root.resolve("upstream"), "Notch", "swordfish1");

        assertTrue(LoginStore.findByUsername(root, "notch").isPresent());
    }

    @Test
    void aPlayerWhoNeverLoggedInHasNoRecord(@TempDir Path root) throws IOException {
        record(root.resolve("upstream"), "Notch", "swordfish1");

        assertTrue(LoginStore.findByUsername(root, "Disgraced_").isEmpty());
        assertTrue(LoginStore.findByUsername(root.resolve("missing"), "Notch").isEmpty());
    }

    @Test
    void aRecordWithoutAStoredPasswordIsNotUsable(@TempDir Path root) throws IOException {
        // The legacy hashed first build wrote one line and no password: there is nothing to log in
        // with, so execute must not treat it as a saved login.
        Files.createDirectories(root);
        Files.writeString(root.resolve(UUID.randomUUID() + ".login"), "Notch\n", StandardCharsets.UTF_8);

        assertTrue(LoginStore.findByUsername(root, "Notch").isEmpty());
    }

    @Test
    void blankUsernamesFindNothing(@TempDir Path root) {
        assertTrue(LoginStore.findByUsername(root, " ").isEmpty());
        assertTrue(LoginStore.findByUsername(root, null).isEmpty());
    }
}
