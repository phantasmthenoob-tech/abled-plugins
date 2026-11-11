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

    /**
     * One saved login found by username rather than UUID, as {@code /loginguard execute as} needs
     * it: the operator names a player, the plugin needs that player's saved password and where the
     * record lives. The folder is the upstream the record was verified against.
     */
    record Stored(String username, String password, String upstreamFolder) {
    }

    /**
     * Finds a record by username anywhere under the logins root - the active upstream's folder and
     * its siblings - case-insensitively, because Bukkit names and record names differ in case.
     * Reads files only, off the main thread (the caller runs this from the async scheduler).
     *
     * <p>Only records written by this plugin count: a player who has never logged in here has
     * nothing saved, which is exactly the "logged in before" condition the command requires.
     */
    static Optional<Stored> findByUsername(Path root, String username) {
        if (username == null || username.isBlank() || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        String wanted = username.trim();
        try (var files = Files.walk(root, 2)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".login"))
                    .map(file -> stored(root, file, wanted))
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Reads one record file and keeps it when its stored username matches. */
    private static Optional<Stored> stored(Path root, Path file, String wanted) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String name = content.lines()
                    .filter(line -> line.startsWith("username:"))
                    .map(line -> line.substring("username:".length()).trim())
                    .findFirst()
                    .orElse("");
            String password = content.lines()
                    .filter(line -> line.startsWith("password:"))
                    .map(line -> line.substring("password:".length()).trim())
                    .findFirst()
                    .orElse("");
            if (name.isEmpty() || password.isEmpty() || !name.equalsIgnoreCase(wanted)) {
                return Optional.empty();
            }
            Path parent = file.getParent();
            // Records sit in logins/<upstream>/; a flat local-only record sits directly in root.
            String folder = parent == null || parent.equals(root) || parent.getFileName() == null
                    ? ""
                    : parent.getFileName().toString();
            return Optional.of(new Stored(name, password, folder));
        } catch (IOException e) {
            return Optional.empty();
        }
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
