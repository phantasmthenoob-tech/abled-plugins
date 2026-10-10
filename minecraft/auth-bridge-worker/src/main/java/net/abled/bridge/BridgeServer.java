package net.abled.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Listens on loopback only. Protocol: one line per request
 * {@code VERIFY <uuid> <LOGIN|REGISTER> <username> <password>} and one line back
 * {@code RESULT <uuid> <VERDICT> [detail]}.
 *
 * <p>Binding is explicit: this socket must never be reachable from outside the host, since
 * passwords cross it. One verify session runs on one dedicated client connection upstream at a
 * time; isolated sessions are independent.
 */
final class BridgeServer {

    private final int port;
    private final Function<Request, Result> handler;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bridge-verify");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    BridgeServer(int port, Function<Request, Result> handler) {
        this.port = port;
        this.handler = handler;
    }

    void start() throws IOException {
        ServerSocket server = new ServerSocket(port, 64, InetAddress.getLoopbackAddress());
        running.set(true);
        Thread acceptor = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket socket = server.accept();
                    pool.submit(() -> serve(socket));
                } catch (IOException e) {
                    if (running.get()) {
                        // brief hiccup; keep accepting
                    }
                }
            }
        }, "bridge-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void stop() {
        running.set(false);
        pool.shutdownNow();
    }

    private void serve(Socket socket) {
        try (socket) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Request request = parse(line);
                    // The handler owns wiping the password after the attempt.
                    Result result;
                    try {
                        result = handler.apply(request);
                    } finally {
                        request.wipe();
                    }
                    out.println(result.wire());
                } catch (IllegalArgumentException e) {
                    out.println("RESULT " + UUID.randomUUID() + " UPSTREAM_TIMEOUT malformed request");
                }
            }
        } catch (IOException e) {
            // client went away mid-session; the plugin's per-request timeout covers it
        }
    }

    private Request parse(String line) {
        String[] parts = line.trim().split(" ", 5);
        if (parts.length != 5 || !parts[0].equals("VERIFY")) {
            throw new IllegalArgumentException("bad request line");
        }
        UUID id = UUID.fromString(parts[1]);
        Request.Action action = Request.Action.valueOf(parts[2]);
        String username = parts[3];
        String password = parts[4];
        return new Request(id, username, password.toCharArray(), action);
    }
}
