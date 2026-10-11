package net.abled.loginguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Plugin side of the bridge. Sends one verify request to the running worker and waits, bounded,
 * for its verdict. Any socket failure, malformed reply or wait timeout resolves to a deny-class
 * verdict: the bridge is fail-closed by construction.
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

    /**
     * Blocking verification. Returns before {@code waitMs} elapses only with a real classified
     * verdict; every failure mode collapses to deny-verdicts.
     */
    Verdict verify(String username, String password, LoginGuardPlugin.Mode action) {
        UUID id = UUID.randomUUID();
        BlockingQueue<Verdict> answer = new ArrayBlockingQueue<>(1);
        try {
            return attempt(id, username, password, action);
        } catch (IOException dead) {
            if (revival == null) {
                logger.accept("bridge: worker unreachable: " + dead.getMessage());
                return Verdict.UPSTREAM_DOWN;
            }
            // The worker died at runtime (crash, host restart, port steal). One revive, then
            // one retry keeps a single transient failure from turning into a missing worker
            // until the next server restart.
            boolean revived;try {
                revived = revival.get();
            } catch (RuntimeException e) {
                revived = false;
            }
            if (!revived) {
                logger.accept("bridge: worker unreachable and could not be revived; denying");
                return Verdict.UPSTREAM_DOWN;
            }
            try {
                return attempt(id, username, password, action);
            } catch (IOException still) {
                logger.accept("bridge: worker unreachable after revive: " + still.getMessage());
                return Verdict.UPSTREAM_DOWN;
            } catch (IllegalArgumentException still) {
                logger.accept("bridge: unparsable worker reply after revive; denying");
                return Verdict.UPSTREAM_TIMEOUT;
            }
        } catch (IllegalArgumentException e) {
            logger.accept("bridge: unparsable worker reply; denying");
            return Verdict.UPSTREAM_TIMEOUT;
        }
    }

    private Verdict attempt(UUID id, String username, String password, LoginGuardPlugin.Mode action)
            throws IOException, IllegalArgumentException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), workerPort)) {
            socket.setSoTimeout((int) waitMs);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println("VERIFY " + id + " " + (action == LoginGuardPlugin.Mode.REGISTER ? "REGISTER" : "LOGIN")
                    + " " + username + " " + password);
            String line = in.readLine();
            if (line == null) {
                return Verdict.UPSTREAM_DOWN;
            }
            Result result = Result.parse(line);
            if (!result.requestId().equals(id)) {
                logger.accept("bridge: mismatched result id; denying");
                return Verdict.UPSTREAM_TIMEOUT;
            }
            if (result.detail() != null && !result.detail().isBlank()) {
                logger.accept("bridge: worker detail: " + result.detail());
            }
            return result.verdict();
        }
    }
}
