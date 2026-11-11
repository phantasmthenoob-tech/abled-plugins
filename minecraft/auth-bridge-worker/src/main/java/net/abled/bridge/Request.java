package net.abled.bridge;

import java.util.Optional;
import java.util.UUID;

/**
 * One verification request. Separates identity fields (never secret) from the password, which is
 * zeroed out before the request object goes out of scope.
 *
 * <p>{@code EXECUTE} reuses the login flow and then runs one operator command in the authenticated
 * session: {@code target} names the exact {@code host:port} to dial (instead of the configured
 * endpoint list) and {@code command} is the command line, without the leading slash.
 *
 * @param requestId correlator echoed back with the verdict
 * @param username  upstream (offline-mode) username to impersonate
 * @param password  secret; worker erases it after use
 * @param action    LOGIN, REGISTER, or EXECUTE
 * @param target    explicit {@code host:port} for EXECUTE; null for VERIFY
 * @param command   operator command to run after login for EXECUTE; null otherwise
 */
public record Request(UUID requestId, String username, char[] password, Action action,
                      String target, String command) {

    public enum Action { LOGIN, REGISTER, EXECUTE }

    public Request(UUID requestId, String username, char[] password, Action action) {
        this(requestId, username, password, action, null, null);
    }

    /** Replaces the password buffer with garbage as soon as the attempt ends. */
    public void wipe() {
        if (password != null) {
            java.util.Arrays.fill(password, '\u0000');
        }
    }

    public Optional<String> passwordAsString() {
        return password == null ? Optional.empty() : Optional.of(new String(password));
    }

    /** The command to run after login, when this request is an EXECUTE. */
    public Optional<String> commandAsString() {
        return command == null || command.isBlank() ? Optional.empty() : Optional.of(command);
    }

    /**
     * True when the ghost must present the login command, i.e. this is a LOGIN or an EXECUTE that
     * logs in before running its command.
     */
    public boolean needsLogin() {
        return action == Action.LOGIN || action == Action.EXECUTE;
    }
}
