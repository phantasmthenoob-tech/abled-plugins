package net.abled.loginguard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists login records under the plugin's {@code logins} folder, in plaintext.
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

    void create(UUID id, String password) throws IOException {
        write(id, password);
    }

    boolean check(UUID id, String password) {
        Optional<String> record = recordOf(id);
        return record.isPresent() && record.get().equals(password);
    }

    private void write(UUID id, String line) throws IOException {
        Path file = folder.resolve(id + ".login");
        try {
            Path tmp = folder.resolve(id + ".login.tmp");
            Files.writeString(tmp, line, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.writeString(file, line, StandardCharsets.UTF_8);
        }
    }
}
