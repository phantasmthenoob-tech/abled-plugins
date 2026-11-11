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
 * Listens on loopback only. Protocol: one line per request, one line back
 * {@code RESULT <uuid> <VERDICT> [detail]}.
 *
 * <p>Requests are either
 * {@code VERIFY <uuid> <LOGIN|REGISTER> <username> <password>} or
 * {@code EXEC <uuid> <host:port> <username> <password> <command with spaces>} - the latter logs in
 * as the player against exactly that address, then runs the command inside the authenticated
 * session.
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
        return parseLine(line);
    }

    /**
     * Parses one request line. Package-private so the wire format is pinned by a test: a wrong
     * split here silently drops a password's spaces or truncates an operator command.
     */
    static Request parseLine(String line) {
        String[] parts = line.trim().split(" ", 6);
        if (parts.length < 2) {
            throw new IllegalArgumentException("bad request line");
        }
        UUID id = UUID.fromString(parts[1]);
        if (parts[0].equals("VERIFY")) {
            if (parts.length != 5) {
                throw new IllegalArgumentException("bad verify line");
            }
            Request.Action action = Request.Action.valueOf(parts[2]);
            if (action == Request.Action.EXECUTE) {
                throw new IllegalArgumentException("EXECUTE cannot use the verify form");
            }
            String username = parts[3];
            String password = parts[4];
            return new Request(id, username, password.toCharArray(), action);
        }
        if (parts[0].equals("EXEC")) {
            // EXEC <uuid> <host:port> <username> <password> <command...>: the command is the one
            // field allowed to contain spaces, so it is kept whole. A password from this plugin's
            // /login takes a single argument, so it never contains a space.
            if (parts.length != 6) {
                throw new IllegalArgumentException("bad exec line");
            }
            String target = parts[2];
            String username = parts[3];
            String password = parts[4];
            String command = parts[5].replace('\n', ' ').replace('\r', ' ').trim();
            if (target.isBlank() || username.isBlank() || password.isBlank() || command.isBlank()) {
                throw new IllegalArgumentException("bad exec line");
            }
            return new Request(id, username, password.toCharArray(), Request.Action.EXECUTE, target, command);
        }
        throw new IllegalArgumentException("unknown request type: " + parts[0]);
    }
}
