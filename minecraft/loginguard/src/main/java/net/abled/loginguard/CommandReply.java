package net.abled.loginguard;

import java.util.Locale;

/**
 * How to read the bridge's answer to an executed command: accepted, refused, or the ghost was
 * dropped. Bukkit-free on purpose, so the wording can be unit-tested with real upstream text.
 *
 * <p>Vanilla answers a command it refuses with translation keys rather than English
 * ({@code command.unknown.command}, {@code command.context.here}, {@code commands.setblock.failed}),
 * so a report that only looked for "unknown command" called a refusal a success.
 */
final class CommandReply {

    private CommandReply() {
    }

    /** The upstream reached the command and refused it (permissions, bad argument, blocked). */
    static boolean looksRefused(String detail) {
        if (detail == null) {
            return false;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("the upstream refused")
                || lower.contains("command.unknown")
                || lower.contains("command.context")
                || lower.contains("unknown command")
                || lower.contains("unknown or incomplete")
                || lower.contains(".failed")
                || lower.contains("no permission")
                || lower.contains("don't have permission")
                || lower.contains("do not have permission")
                || lower.contains("not allowed")
                || lower.contains("not permitted")
                || lower.contains("cannot be placed")
                || lower.contains("position is not loaded");
    }

    /** The server kicked the ghost instead of answering (AuthMe's quick-command guard, etc.). */
    static boolean looksDropped(String detail) {
        return detail != null && detail.toLowerCase(Locale.ROOT).contains("disconnected the ghost");
    }

    /**
     * The account's own command tree lacked the verb, which is proof that the account is not op
     * there - unlike an "unknown command" reply alone, which a blocked command could also cause.
     */
    static boolean namesMissingVerb(String detail) {
        return detail != null && detail.toLowerCase(Locale.ROOT)
                .contains("command tree for this account has no");
    }

    /** Verdict-prefixed detail for an execution that the upstream did not accept. */
    static String refusedNotice(String where, String detail) {
        return "\u00A7e" + where + ": " + detail;
    }
}
