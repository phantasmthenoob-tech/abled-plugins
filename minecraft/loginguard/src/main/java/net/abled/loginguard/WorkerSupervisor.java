package net.abled.loginguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Spawns and watches the headless bridge worker as a child process. Restarts it on unexpected
 * exit; the expected lifecycle is exactly one spawn per plugin enable and one kill on disable.
 *
 * <p>The worker jar lives in {@code plugins/LoginGuard/worker/AuthBridgeWorker.jar} - deploy it
 * alongside the plugin jar (or let it ride inside the release). No classpath games.
 */
final class WorkerSupervisor {

    private final Path workerJar;
    private final int controlPort;
    private final String upstreamHost;
    private final int upstreamPort;
    private final long upstreamTimeoutMs;
    private final Consumer<String> logger;
    private Process process;

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
            return true;
        }
        if (!Files.isRegularFile(workerJar)) {
            logger.accept("worker jar missing at " + workerJar + " - verification will fail closed");
            return false;
        }
        try {
            String java = System.getProperty("java.home") + "/bin/java";
            process = new ProcessBuilder(
                    java, "-jar", workerJar.toAbsolutePath().toString(),
                    String.valueOf(controlPort), upstreamHost, String.valueOf(upstreamPort),
                    String.valueOf(upstreamTimeoutMs))
                    .redirectErrorStream(true)
                    .start();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (process.isAlive() && (line = in.readLine()) != null) {
                        if (line.contains("READY")) {
                            logger.accept("worker ready on port " + controlPort);
                        } else if (!line.contains("password")) { // never log secrets, even if the worker leaked
                            logger.accept("worker: " + line);
                        }
                    }
                } catch (IOException e) {
                    // stream closed with the process; nothing further
                }
            }, "login-guard-worker-stdout");
            reader.setDaemon(true);
            reader.start();
            return true;
        } catch (IOException e) {
            logger.accept("worker spawn failed: " + e.getMessage());
            process = null;
            return false;
        }
    }

    synchronized void shutdown() {
        if (process != null && process.isAlive()) {
            process.destroy();
            process.destroyForcibly();
        }
        process = null;
    }
}
