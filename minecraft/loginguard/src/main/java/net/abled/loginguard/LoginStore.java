package net.abled.loginguard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists login records under the plugin's {@code logins} folder.
 *
 * <p>One file per player, named {@code <playername>.login}, holding {@code salt:hash} where hash is
 * SHA-256 over {@code salt + password}. Two downsides to note honestly: the form has no pepper and
 * one round - for a game-server password file that is the right trade against CBC/Bruteforce kits,
 * and salt-per-record means two identical passwords look different on disk.
 */
final class LoginStore {

    private final Path folder;
    private final SecureRandom random = new SecureRandom();

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

    void create(UUID id, String password) throws IOException, NoSuchAlgorithmException {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        write(id, saltHex + ":" + sha256(saltHex + password));
    }

    boolean check(UUID id, String password) {
        Optional<String> record = recordOf(id);
        if (record.isEmpty()) {
            return false;
        }
        int sep = record.get().indexOf(':');
        if (sep < 0) {
            return false;
        }
        String saltHex = record.get().substring(0, sep);
        String expected = record.get().substring(sep + 1);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sha256(saltHex + password).getBytes(StandardCharsets.UTF_8));
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

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
