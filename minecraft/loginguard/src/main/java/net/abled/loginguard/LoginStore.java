package net.abled.loginguard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists login records under the plugin's {@code logins} folder.
 *
 * <p>One file per player, named {@code <uuid>.login}, plaintext by request, carrying the username
 * and password on labeled lines so an admin can read both at a glance:
 *
 * <pre>
 * username: Notch
 * password: strongpass1
 * </pre>
 *
 * <p>Records written by the very first build held only a salted hash on a single line; those
 * contain no {@code password: } line and are treated as the literal password string there, which
 * will simply fail the check and lead the player to /register again.
 */
final class LoginStore {

    private final Path folder;

    LoginStore(Path folder) throws IOException {
        this.folder = folder;
        Files.createDirectories(folder);
    }

    Optional<String> recordOf(UUID id) {
        Path file = folder.resolve(id + ".login");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    boolean exists(UUID id) {
        return recordOf(id).isPresent();
    }

    void create(UUID id, String username, String password) throws IOException {
        write(id, "username: " + username.trim() + "\npassword: " + password.trim() + "\n");
    }

    boolean check(UUID id, String password) {
        return passwordOf(id)
                .map(p -> p.equals(password.trim()))
                .orElse(false);
    }

    /** The stored password in the clear, for admin inspection or {@link #check}. */
    Optional<String> passwordOf(UUID id) {
        return recordOf(id).map(record ->
                record.lines()
                        .filter(line -> line.startsWith("password:"))
                        .map(line -> line.substring("password:".length()).trim())
                        .findFirst()
                        // legacy single-line record from the hashed first build
                        .orElse(record));
    }

    /** The stored username, shown to admins looking at the folder. */
    Optional<String> nameOf(UUID id) {
        return recordOf(id).flatMap(record ->
                record.lines()
                        .filter(line -> line.startsWith("username:"))
                        .map(line -> line.substring("username:".length()).trim())
                        .findFirst());
    }

    private void write(UUID id, String content) throws IOException {
        Path file = folder.resolve(id + ".login");
        try {
            Path tmp = folder.resolve(id + ".login.tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }
}
