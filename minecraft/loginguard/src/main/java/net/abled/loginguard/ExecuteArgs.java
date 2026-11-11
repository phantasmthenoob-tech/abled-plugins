package net.abled.loginguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses {@code /loginguard execute as <username> <host:port> <command...>}.
 *
 * <p>The documented shape mirrors Minecraft's own {@code /execute as <target> run <command>}: who
 * to act as, on which server, and what to run there. Bukkit hands the executor the whole argument
 * array ({@code ["execute", "as", ...]}), so the literal subcommand words are consumed here.
 *
 * <p>Bukkit-free on purpose: {@code paper-api} is {@code compileOnly}, so the shapes that broke in
 * production are pinned by unit tests instead.
 */
final class ExecuteArgs {

    /** The three fields the worker needs, already validated. */
    record Parsed(String username, String endpoint, String command) {
    }

    private ExecuteArgs() {
    }

    static String usage() {
        return "/loginguard execute as <username> <host:port> <command>";
    }

    /**
     * Reads the words after {@code /loginguard}. Empty when the shape is wrong or a field is
     * unusable, so the caller prints the usage line instead of dialling a typo.
     *
     * @param defaultPort port used when the address is a bare host ({@code 51.161.140.150} rather
     *                    than {@code 51.161.140.150:25565}); the caller passes the configured
     *                    upstream port so a bare "server ip" still means something
     */
    static Optional<Parsed> parse(String[] args, int defaultPort) {
        List<String> words = new ArrayList<>(args == null ? List.of() : List.of(args));
        if (words.size() < 2 || !words.get(0).equalsIgnoreCase("execute") || !words.get(1).equalsIgnoreCase("as")) {
            return Optional.empty();
        }
        if (words.size() < 5) {
            // execute as <username> <host:port> needs at least one command word after them.
            return Optional.empty();
        }
        String username = words.get(2).trim();
        String endpoint = words.get(3).trim();
        if (!isUsername(username)) {
            return Optional.empty();
        }
        if (EndpointProbe.parse(endpoint) == null) {
            // "server ip" without a port: only a bare host may be completed, never a typo.
            if (!endpoint.matches("[A-Za-z0-9.-]+")) {
                return Optional.empty();
            }
            endpoint = endpoint + ":" + defaultPort;
        }
        String command = String.join(" ", words.subList(4, words.size())).trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(username, endpoint, command));
    }

    /**
     * Offline-mode names are {@code [A-Za-z0-9_]{1,16}}; AuthMe accounts follow the same shape.
     * Rejecting anything else keeps a stray word from being sent upstream as a username.
     */
    static boolean isUsername(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }
}
