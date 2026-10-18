package net.abled.loginguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Spawns and watches the headless bridge worker as a child process. Restarts it on unexpected
 * exit; the expected lifecycle is exactly one spawn per plugin enable and one kill on disable.
 *
 * <p>The worker jar is bundled inside the plugin jar at {@code /AuthBridgeWorker.jar} and is
 * extracted to {@code plugins/LoginGuard/worker/AuthBridgeWorker.jar} whenever it is missing, so
 * a deploy is a single jar and a wiped data folder cannot strand the bridge without its worker.
 */
final class WorkerSupervisor {

    private final Path workerJar;
    private final int controlPort;
    /** {@code host:port[,host:port...]}; later entries are fallbacks the worker tries in order. */
    private final String upstreamEndpoints;
    private final long upstreamTimeoutMs;
    /** Local source address the worker binds its upstream connections to; blank for the OS default. */
    private final String bindAddress;
    /** Proxy the worker dials the upstream through; blank for a direct connection. */
    private final String proxy;
    private final Consumer<String> logger;
    private Process process;
    private volatile boolean ready;

    WorkerSupervisor(Path pluginDataFolder, int controlPort, String upstreamEndpoints,
                     long upstreamTimeoutMs, String bindAddress, String proxy, Consumer<String> logger) {
        this.workerJar = pluginDataFolder.resolve("worker").resolve("AuthBridgeWorker.jar");
        this.controlPort = controlPort;
        this.upstreamEndpoints = upstreamEndpoints;
        this.upstreamTimeoutMs = upstreamTimeoutMs;
        this.bindAddress = bindAddress == null ? "" : bindAddress.trim();
        this.proxy = proxy == null ? "" : proxy.trim();
        this.logger = logger;
    }

    /** Starts (or restarts) the worker. Safe to call repeatedly; idempotent while alive. */
    synchronized boolean ensureRunning() {
        if (process != null && process.isAlive()) {
            return ready;
        }
        ready = false;
        // Always re-extract the bundled worker. The old extract-only-if-missing rule left the
        // first build's worker in plugins/LoginGuard/worker forever, so updating the plugin
        // silently kept running the stale worker next to the new plugin.
        extractBundledWorker();
        if (!Files.isRegularFile(workerJar)) {
            logger.accept("worker jar missing at " + workerJar + " and bundled copy unavailable"
                    + " - verification will fail closed");
            return false;
        }
        try {
            String java = System.getProperty("java.home") + "/bin/java";
            List<String> command = new ArrayList<>();
            command.add(java);
            // JDK 24+ warns on every start that netty loads a native library from an unnamed
            // module; the flag exists only from 24 on, so older JVMs must not receive it.
            if (Runtime.version().feature() >= 24) {
                command.add("--enable-native-access=ALL-UNNAMED");
            }
            command.add("-jar");
            command.add(workerJar.toAbsolutePath().toString());
            command.add(String.valueOf(controlPort));
            command.add(upstreamEndpoints);
            command.add(String.valueOf(upstreamTimeoutMs));
            if (!bindAddress.isEmpty()) {
                command.add("bind=" + bindAddress);
            }
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (!proxy.isEmpty()) {
                // Passed through the environment rather than argv: a proxy with credentials would
                // otherwise be readable by every user on the host through the process list.
                builder.environment().put("AUTHBRIDGE_PROXY", proxy);
            }
            process = builder.start();
            // The stdout reader must exist before the READY wait: it is what sets ready.
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (process.isAlive() && (line = in.readLine()) != null) {
                        if (line.contains("READY")) {
                            ready = true;
                            logger.accept("worker ready on port " + controlPort);
                        } else if (!looksSecret(line)) {
                            logger.accept("worker: " + line);
                        }
                    }
                    // The worker exited: forget READY so a revive respawns it.
                    ready = false;
                } catch (IOException e) {
                    // stream closed with the process; nothing further
                }
            }, "login-guard-worker-stdout");
            reader.setDaemon(true);
            reader.start();
            // The process being alive is not the worker being up: the JVM takes a moment to
            // bind the control port, and a /login in that window saw connection refused.
            // READY (printed after the port binds) is the real signal; wait for it, bounded.
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (ready) {
                    return true;
                }
                if (process == null || !process.isAlive()) {
                    logger.accept("worker died before READY; verification will fail closed");
                    process = null;
                    return false;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            logger.accept("worker did not reach READY within 15s; verification will fail closed");
            return false;
        } catch (IOException e) {
            logger.accept("worker spawn failed: " + e.getMessage());
            process = null;
            return false;
        }
    }

    /**
     * Last line of defence before the server console: drop anything credential-shaped. The worker
     * already withholds passwords, but a regression there must not print a player's password into
     * a log file. Matching the words is intentionally broad - losing a diagnostic line is cheap.
     */
    private static boolean looksSecret(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("passwort")
                || lower.contains("/login ") || lower.contains("/register ");
    }

    /**
     * Copies the worker jar bundled inside the plugin jar to the data folder, replacing any
     * existing copy. Returns false without side effects when the resource is absent
     * (foreign-built jar, shaded fat jar, or extraction I/O failure).
     */
    private boolean extractBundledWorker() {
        try (InputStream in = WorkerSupervisor.class.getResourceAsStream("/AuthBridgeWorker.jar")) {
            if (in == null) {
                return false;
            }
            Files.createDirectories(workerJar.getParent());
            Files.copy(in, workerJar, StandardCopyOption.REPLACE_EXISTING);
            logger.accept("extracted bundled worker jar to " + workerJar);
            return true;
        } catch (IOException e) {
            logger.accept("could not extract bundled worker jar: " + e.getMessage());
            return false;
        }
    }

    /**
     * Brings the worker back after an unexpected death. Idempotent while it is already live and
     * ready; returns false when the jar is missing or the respawn never reaches READY.
     */
    synchronized boolean revive() {
        if (process != null && process.isAlive() && ready) {
            return true;
        }
        if (process != null && process.isAlive()) {
            // half-started (no READY in 15s) or wedged: clear it and respawn
            process.destroy();
            process.destroyForcibly();
            process = null;
        }
        logger.accept("worker not reachable; respawning");
        return ensureRunning();
    }

    synchronized void shutdown() {
        if (process != null && process.isAlive()) {
            process.destroy();
            process.destroyForcibly();
        }
        process = null;
    }
}
