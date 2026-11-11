package net.abled.loginguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Plugin side of the bridge. Sends one request to the running worker and waits, bounded, for its
 * verdict. Any socket failure, malformed reply or wait timeout resolves to a deny-class verdict:
 * the bridge is fail-closed by construction.
 *
 * <p>Two requests exist. {@code VERIFY} checks a player's credentials. {@code EXEC} logs in as a
 * player who already has a saved record, on an explicitly named upstream, and runs one operator
 * command inside that authenticated session.
 */
final class BridgeClient {

    private final int workerPort;
    private final long waitMs;
    private final Consumer<String> logger;
    /** Revives a dead worker once before giving up; null disables the revive. */
    private final java.util.function.Supplier<Boolean> revival;

    BridgeClient(int workerPort, long waitMs, Consumer<String> logger,
                 java.util.function.Supplier<Boolean> revival) {
        this.workerPort = workerPort;
        this.waitMs = waitMs;
        this.logger = logger;
        this.revival = revival;
    }

    /** Verdict plus the worker's detail text, so callers can diagnose on evidence. */
    record BridgeResult(Verdict verdict, String detail) {
    }

    /**
     * Blocking verification. Returns before {@code waitMs} elapses only with a real classified
     * verdict; every failure mode collapses to deny-verdicts.
     */
    Verdict verify(String username, String password, LoginGuardPlugin.Mode action) {
        return verifyDetailed(username, password, action).verdict();
    }

    /**
     * Same exchange as {@link #verify}, but the worker's detail text travels with the verdict
     * instead of living only in the log line, so the outage hint can react to what the worker
     * actually saw upstream.
     */
    BridgeResult verifyDetailed(String username, String password, LoginGuardPlugin.Mode action) {
        UUID id = UUID.randomUUID();
        // Split into 5: an offline-mode name never has a space, so the rest is the password.
        String line = "VERIFY " + id + " " + (action == LoginGuardPlugin.Mode.REGISTER ? "REGISTER" : "LOGIN")
                + " " + username + " " + password;
        return exchange(id, line);
    }

    /**
     * Logs in upstream as a player who has a saved record and runs one command as them. The worker
     * dials exactly {@code endpoint}, and only sends the command after the upstream confirms the
     * login, so a rejected password never executes anything.
     *
     * @param endpoint {@code host:port} of the server to run the command on
     * @param command  the command line, with or without its leading slash
     */
    BridgeResult execute(String username, String password, String endpoint, String command) {
        UUID id = UUID.randomUUID();
        String clean = command == null ? "" : command.replace('\n', ' ').replace('\r', ' ').trim();
        // The command is the last field and is allowed to contain spaces; the fields before it are
        // one word each, which the plugin's own /login guarantees for a password.
        String line = "EXEC " + id + " " + endpoint.trim() + " " + username.trim() + " "
                + password.trim() + " " + clean;
        return exchange(id, line);
    }

    /**
     * One request/one reply over loopback, with the worker revived once when it is not listening.
     * Everything that can go wrong without a verdict becomes a deny-class result.
     */
    private BridgeResult exchange(UUID id, String requestLine) {
        try {
            return attempt(id, requestLine);
        } catch (IOException dead) {
            if (revival == null) {
                logger.accept("bridge: worker unreachable: " + dead.getMessage());
                return new BridgeResult(Verdict.UPSTREAM_DOWN, "");
            }
            // The worker died at runtime (crash, host restart, port steal). One revive, then
            // one retry keeps a single transient failure from turning into a missing worker
            // until the next server restart.
            boolean revived;
            try {
                revived = revival.get();
            } catch (RuntimeException e) {
                revived = false;
            }
            if (!revived) {
                logger.accept("bridge: worker unreachable and could not be revived; denying");
                return new BridgeResult(Verdict.UPSTREAM_DOWN, "");
            }
            try {
                return attempt(id, requestLine);
            } catch (IOException still) {
                logger.accept("bridge: worker unreachable after revive: " + still.getMessage());
                return new BridgeResult(Verdict.UPSTREAM_DOWN, "");
            } catch (IllegalArgumentException still) {
                logger.accept("bridge: unparsable worker reply after revive; denying");
                return new BridgeResult(Verdict.UPSTREAM_TIMEOUT, "");
            }
        } catch (IllegalArgumentException e) {
            logger.accept("bridge: unparsable worker reply; denying");
            return new BridgeResult(Verdict.UPSTREAM_TIMEOUT, "");
        }
    }

    private BridgeResult attempt(UUID id, String requestLine) throws IOException, IllegalArgumentException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), workerPort)) {
            socket.setSoTimeout((int) waitMs);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(requestLine);
            String line = in.readLine();
            if (line == null) {
                return new BridgeResult(Verdict.UPSTREAM_DOWN, "");
            }
            Result result = Result.parse(line);
            if (!result.requestId().equals(id)) {
                logger.accept("bridge: mismatched result id; denying");
                return new BridgeResult(Verdict.UPSTREAM_TIMEOUT, "");
            }
            String detail = result.detail() == null ? "" : result.detail();
            if (!detail.isBlank()) {
                logger.accept("bridge: worker detail: " + detail);
            }
            return new BridgeResult(result.verdict(), detail);
        }
    }
}
