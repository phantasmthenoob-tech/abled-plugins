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

    BridgeClient(int workerPort, long waitMs, Consumer<String> logger) {
        this.workerPort = workerPort;
        this.waitMs = waitMs;
        this.logger = logger;
    }

    /**
     * Blocking verification. Returns before {@code waitMs} elapses only with a real classified
     * verdict; every failure mode collapses to deny-verdicts.
     */
    Verdict verify(String username, String password, LoginGuardPlugin.Mode action) {
        UUID id = UUID.randomUUID();
        BlockingQueue<Verdict> answer = new ArrayBlockingQueue<>(1);
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
            return result.verdict();
        } catch (IOException e) {
            logger.accept("bridge: worker unreachable: " + e.getMessage());
            return Verdict.UPSTREAM_DOWN;
        } catch (IllegalArgumentException e) {
            logger.accept("bridge: unparsable worker reply; denying");
            return Verdict.UPSTREAM_TIMEOUT;
        }
    }
}
