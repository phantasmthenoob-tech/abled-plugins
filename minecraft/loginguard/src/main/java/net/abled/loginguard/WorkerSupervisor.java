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
    private final String upstreamHost;
    private final int upstreamPort;
    private final long upstreamTimeoutMs;
    private final Consumer<String> logger;
    private Process process;
    private volatile boolean ready;

    WorkerSupervisor(Path pluginDataFolder, int controlPort, String upstreamHost, int upstreamPort,
                     long upstreamTimeoutMs, Consumer<String> logger) {
        this.workerJar = pluginDataFolder.resolve("worker").resolve("AuthBridgeWorker.jar");
        this.controlPort = controlPort;
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.upstreamTimeoutMs = upstreamTimeoutMs;
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
            command.add(upstreamHost);
            command.add(String.valueOf(upstreamPort));
            command.add(String.valueOf(upstreamTimeoutMs));
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            // The stdout reader must exist before the READY wait: it is what sets ready.
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (process.isAlive() && (line = in.readLine()) != null) {
                        if (line.contains("READY")) {
                            ready = true;
                            logger.accept("worker ready on port " + controlPort);
                        } else if (!line.contains("password")) { // never log secrets, even if the worker leaked
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
