package net.abled.loginguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Answers the two questions a refused bridge connection raises: does this endpoint answer at all,
 * and which TCP ports does this machine actually listen on?
 *
 * <p>Hosted Minecraft servers are normally published through a NAT allocation: players connect to
 * {@code play.example.com:50252}, while the server itself listens on 25565 inside its container.
 * A box almost never reaches its own published address (no hairpin NAT), and nothing listens on
 * the published port at loopback - so both attempts are refused although the server is running.
 * The listening-port list is what turns that dead end into an address the operator can configure.
 */
final class EndpointProbe {

    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final String[] TCP_TABLES = {"/proc/net/tcp", "/proc/net/tcp6"};

    private EndpointProbe() {
    }

    /** One endpoint's answer. {@code address} is the resolution, null when it never resolved. */
    record Result(String endpoint, InetAddress address, int port, boolean reachable,
                  long millis, String error) {

        /** One console line: what was dialled, where it resolved, and the outcome. */
        String describe() {
            String resolved = address == null ? "" : address.getHostAddress();
            if (reachable) {
                return endpoint + " reachable (" + millis + " ms, " + resolved + ")";
            }
            String why = error == null || error.isBlank() ? "no answer" : error;
            return endpoint + " NOT reachable: " + why
                    + (resolved.isEmpty() ? "" : " (resolved to " + resolved + ")");
        }
    }

    /**
     * Splits {@code host:port} and validates the port. Returns {@code {host, port}} or null when
     * the text is not a usable endpoint, so a typo is reported instead of dialled.
     */
    static String[] parse(String spec) {
        if (spec == null) {
            return null;
        }
        String text = spec.trim();
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            return null;
        }
        String host = text.substring(0, colon).trim();
        // [::1]:25565 is how an IPv6 literal is written; the brackets are not part of the host.
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(text.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return new String[]{host, String.valueOf(port)};
    }

    static Result probe(String endpoint, String bindAddress) {
        return probe(endpoint, bindAddress, DEFAULT_TIMEOUT_MS);
    }

    /** Resolves and connects once, bounded; never throws. */
    static Result probe(String endpoint, String bindAddress, int timeoutMs) {
        String[] parts = parse(endpoint);
        if (parts == null) {
            return new Result(endpoint, null, -1, false, 0, "not host:port");
        }
        InetAddress resolved = null;
        int port = Integer.parseInt(parts[1]);
        long started = System.nanoTime();
        try {
            resolved = InetAddress.getByName(parts[0]);
            try (Socket socket = new Socket()) {
                if (bindAddress != null && !bindAddress.isBlank()) {
                    socket.bind(new InetSocketAddress(bindAddress.trim(), 0));
                }
                socket.connect(new InetSocketAddress(resolved, port), timeoutMs);
            }
            return new Result(endpoint, resolved, port, true, elapsedMillis(started), null);
        } catch (IOException e) {
            return failed(endpoint, resolved, port, started, e);
        } catch (RuntimeException e) {
            // An unusable bind-address surfaces as an unchecked UnresolvedAddressException; a
            // diagnostic must report it, not die inside the async task and print nothing.
            return failed(endpoint, resolved, port, started, e);
        }
    }

    private static Result failed(String endpoint, InetAddress resolved, int port, long startedNanos,
                                 Exception e) {
        return new Result(endpoint, resolved, port, false, elapsedMillis(startedNanos),
                e.getClass().getSimpleName()
                        + (e.getMessage() == null || e.getMessage().isBlank() ? "" : " (" + e.getMessage() + ")"));
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** True when the address belongs to this machine (any interface). */
    static boolean isLocalAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        try {
            return java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).stream()
                    .flatMap(nic -> java.util.Collections.list(nic.getInetAddresses()).stream())
                    .anyMatch(address::equals);
        } catch (IOException e) {
            return false;
        }
    }

    /** Distinct TCP ports this machine listens on, ascending; empty when /proc is unavailable. */
    static List<Integer> listeningPorts() {
        Set<Integer> ports = new TreeSet<>();
        for (String table : TCP_TABLES) {
            Path path = Path.of(table);
            if (!Files.isReadable(path)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(path)) {
                ports.addAll(parseListeningPorts(in));
            } catch (IOException e) {
                // An unreadable /proc entry only costs this diagnostic, never the bridge.
            }
        }
        return List.copyOf(ports);
    }

    /** {@code "25565, 4519"}, or a sentence saying the table is not visible here. */
    static String describeListeningPorts() {
        List<Integer> ports = listeningPorts();
        if (ports.isEmpty()) {
            return "none visible (no readable /proc/net/tcp on this host)";
        }
        return String.join(", ", ports.stream().map(String::valueOf).toList());
    }

    /**
     * Parses the kernel's TCP table. The local-address column is {@code HEXADDR:HEXPORT} and only
     * rows in state {@code 0A} (LISTEN) count. Kept separate from the file read so the parse is
     * testable on any host, with or without /proc.
     */
    static List<Integer> parseListeningPorts(InputStream in) throws IOException {
        Set<Integer> ports = new TreeSet<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] columns = line.trim().split("\\s+");
            // sl local_address rem_address st ... ; the header and short rows are skipped.
            if (columns.length < 4 || !columns[0].endsWith(":")) {
                continue;
            }
            if (!"0A".equalsIgnoreCase(columns[3])) {
                continue;
            }
            int colon = columns[1].lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            try {
                ports.add(Integer.parseInt(columns[1].substring(colon + 1), 16));
            } catch (NumberFormatException e) {
                // One unreadable row must not lose the rest of the table.
            }
        }
        return List.copyOf(ports);
    }
}
