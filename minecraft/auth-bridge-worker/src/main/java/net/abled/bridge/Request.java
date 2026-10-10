package net.abled.bridge;

import java.util.Optional;
import java.util.UUID;

/**
 * One verification request. Separates identity fields (never secret) from the password, which is
 * zeroed out before the request object goes out of scope.
 *
 * @param requestId correlator echoed back with the verdict
 * @param username  upstream (offline-mode) username to impersonate
 * @param password  secret; worker erases it after use
 * @param action    LOGIN or REGISTER against the upstream
 */
public record Request(UUID requestId, String username, char[] password, Action action) {

    public enum Action { LOGIN, REGISTER }

    /** Replaces the password buffer with garbage as soon as the attempt ends. */
    public void wipe() {
        if (password != null) {
            java.util.Arrays.fill(password, '\u0000');
        }
    }

    public Optional<String> passwordAsString() {
        return password == null ? Optional.empty() : Optional.of(new String(password));
    }
}
