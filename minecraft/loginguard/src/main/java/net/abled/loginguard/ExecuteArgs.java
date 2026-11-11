package net.abled.loginguard;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code /loginguard execute as <username> <host:port> <command>}.
 *
 * <p>The documented shape mirrors Minecraft's own {@code /execute as <target> run <command>}: who
 * to act as, on which server, and what to run there. Bukkit hands the executor the whole argument
 * array ({@code ["execute", "as", ...]}), so the literal subcommand words are consumed here.
 *
 * <p>A command longer than {@value #MAX_STEP_LENGTH} characters cannot be sent at all: the
 * {@code chat_command} packet's command field is capped at 256 and a server that receives more
 * rejects it and disconnects the client, with no chat error to explain it. Long jobs are therefore
 * written as {@code ;}-separated steps, each within the cap - {@code ;} is not part of SNBT, so
 * ordinary NBT arguments never split by accident.
 *
 * <p>Bukkit-free on purpose: {@code paper-api} is {@code compileOnly}, so the shapes that broke in
 * production are pinned by unit tests instead.
 */
final class ExecuteArgs {

    /** The command field of a player's chat command packet; a longer one gets the client kicked. */
    static final int MAX_STEP_LENGTH = 256;

    /**
     * A one-off job, not a script. Filling a 27-slot chest with fully enchanted gear takes around
     * 46 steps (one setblock, one item per slot, and one data-modify per potion effect), so the
     * ceiling sits above that rather than at a round number.
     */
    static final int MAX_STEPS = 64;

    /** The three fields the worker needs, already validated. */
    record Parsed(String username, String endpoint, List<String> steps) {

        /** Every step on one line, the form the bridge request carries. */
        String command() {
            return String.join("; ", steps);
        }

        /** {@code /setblock ...; /item replace ...}, the form an operator can read back. */
        String display() {
            List<String> slashed = new ArrayList<>();
            for (String step : steps) {
                slashed.add("/" + step);
            }
            return String.join("; ", slashed);
        }

        /**
         * The same line cut to a screenful: a 46-step chest job is thousands of characters, and a
         * chat message that long is unreadable. The steps that run are always all of them.
         */
        String preview() {
            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (String step : steps) {
                String piece = (shown == 0 ? "/" : "; /") + step;
                if (shown > 0 && out.length() + piece.length() > PREVIEW_CHARS) {
                    break;
                }
                out.append(piece);
                shown++;
            }
            if (shown < steps.size()) {
                out.append("; ... (+").append(steps.size() - shown).append(" more steps)");
            }
            return out.toString();
        }
    }

    /** Roughly one chat line worth of command, before the rest is summarised as a count. */
    private static final int PREVIEW_CHARS = 220;

    /** Either a ready request or the reason this shape was refused, so nothing is guessed. */
    record Parse(Parsed parsed, String problem) {

        boolean ok() {
            return parsed != null;
        }
    }

    private ExecuteArgs() {
    }

    static String usage() {
        return "/loginguard execute as <username> <host:port> <command>"
                + " (long jobs: cmd1 ; cmd2 ; ..., each under " + MAX_STEP_LENGTH
                + " characters, at most " + MAX_STEPS + " commands)";
    }

    /**
     * Reads the words after {@code /loginguard}. A refusal carries the sentence to show, so a
     * wrong shape, an unusable field and an oversized command each explain themselves.
     *
     * @param defaultPort port used when the address is a bare host ({@code 51.161.140.150} rather
     *                    than {@code 51.161.140.150:25565}); the caller passes the configured
     *                    upstream port so a bare "server ip" still means something
     */
    static Parse parse(String[] args, int defaultPort) {
        List<String> words = new ArrayList<>(args == null ? List.of() : List.of(args));
        if (words.size() < 2 || !words.get(0).equalsIgnoreCase("execute") || !words.get(1).equalsIgnoreCase("as")) {
            return refused("usage: " + usage());
        }
        if (words.size() < 5) {
            // execute as <username> <host:port> needs at least one command word after them.
            return refused("usage: " + usage());
        }
        String username = words.get(2).trim();
        String endpoint = words.get(3).trim();
        if (!isUsername(username)) {
            return refused("'" + username + "' is not a player name (1-16 letters, digits or _)");
        }
        if (EndpointProbe.parse(endpoint) == null) {
            // "server ip" without a port: only a bare host may be completed, never a typo.
            if (!endpoint.matches("[A-Za-z0-9.-]+")) {
                return refused("'" + endpoint + "' is not a host:port");
            }
            endpoint = endpoint + ":" + defaultPort;
        }
        List<String> steps = steps(String.join(" ", words.subList(4, words.size())));
        if (steps.isEmpty()) {
            return refused("usage: " + usage());
        }
        if (steps.size() > MAX_STEPS) {
            return refused("that is " + steps.size() + " commands - at most " + MAX_STEPS
                    + " are run in one session; split it across two runs");
        }
        for (String step : steps) {
            if (step.length() > MAX_STEP_LENGTH) {
                return refused(tooLong(step));
            }
        }
        return new Parse(new Parsed(username, endpoint, List.copyOf(steps)), null);
    }

    /**
     * Offline-mode names are {@code [A-Za-z0-9_]{1,16}}; AuthMe accounts follow the same shape.
     * Rejecting anything else keeps a stray word from being sent upstream as a username.
     */
    static boolean isUsername(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    /** Splits the command words into steps on ';', dropping the slash players type by habit. */
    private static List<String> steps(String text) {
        List<String> steps = new ArrayList<>();
        for (String piece : text.split(";")) {
            String step = piece.trim();
            if (step.startsWith("/")) {
                step = step.substring(1).trim();
            }
            if (!step.isEmpty()) {
                steps.add(step);
            }
        }
        return steps;
    }

    /** Names the real limit and how to get around it, since no retry can make this command fit. */
    private static String tooLong(String step) {
        return "that step is " + step.length() + " characters and a Minecraft player can only send "
                + MAX_STEP_LENGTH + " - the server disconnects anyone who sends more, with no error."
                + " Split it into ';'-separated steps: /loginguard execute as <name> <host:port>"
                + " setblock <pos> <block> ; item replace block <pos> <slot> with <item>"
                + " (arguments count toward the " + MAX_STEP_LENGTH + ")";
    }

    private static Parse refused(String problem) {
        return new Parse(null, problem);
    }
}
