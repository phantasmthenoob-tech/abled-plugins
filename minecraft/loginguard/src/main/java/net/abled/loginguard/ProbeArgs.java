package net.abled.loginguard;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the words after {@code /loginguard} for the probe command. The documented form is
 * {@code /loginguard probe [host:port]}, so Bukkit hands the executor {@code ["probe",
 * "<host:port>"]} - the literal subcommand word is stripped, not parsed as an endpoint. Both
 * {@code /loginguard probe host:port} and the bare {@code /loginguard host:port} resolve to the
 * endpoint.
 *
 * <p>Bukkit-free on purpose: {@code paper-api} is {@code compileOnly}, so anything touching
 * Bukkit types cannot be unit-tested. This is where the shape that broke in production is pinned.
 */
final class ProbeArgs {

    private ProbeArgs() {
    }

    static List<String> commandWords(String[] args) {
        List<String> words = new ArrayList<>(List.of(args));
        if (!words.isEmpty() && words.get(0).equalsIgnoreCase("probe")) {
            words.remove(0);
        }
        return words;
    }
}
