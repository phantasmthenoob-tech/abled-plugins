package net.abled.loginguard;

import java.util.List;
import java.util.Optional;

/**
 * Turns a bridge verdict plus the worker's detail text into console guidance.
 *
 * <p>Bukkit-free on purpose (paper-api is compileOnly): unit tests pin every shape. The worker
 * decides the verdict, but its detail text names what it last saw upstream, and that evidence
 * decides which hint is honest. Two shapes from the October ableduniversesmp report drove this:
 * a ghost kicked for "the same username is already playing" was reported as UPSTREAM_DOWN with
 * the refused-port hint although the upstream had clearly answered, and a ghost that reached
 * the AuthMe prompt but was never accepted timed out with no hint at all.
 */
final class UpstreamDiagnosis {

    /** One actionable hint, logged once per outage under {@link #key}. */
    record Hint(String key, List<String> lines) {
    }

    private UpstreamDiagnosis() {
    }

    /**
     * @param verdict        the worker's verdict for this attempt
     * @param detail         the worker's detail text (may be null/blank)
     * @param endpoints      endpoints the worker dials, in order
     * @param listeningPorts {@link EndpointProbe#describeListeningPorts()} for this machine
     * @return the hint to log, or empty when the verdict needs no guidance
     */
    static Optional<Hint> diagnose(Verdict verdict, String detail, List<String> endpoints,
            String listeningPorts) {
        String seen = detail == null ? "" : detail.toLowerCase();
        return switch (verdict) {
            case UPSTREAM_DOWN -> Optional.of(duplicateSession(seen)
                    ? duplicateSessionHint()
                    : unreachableHint(endpoints, listeningPorts));
            case UPSTREAM_TIMEOUT -> reachedUpstreamPrompt(seen)
                    ? Optional.of(notAcceptedHint(firstEndpoint(endpoints)))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    /**
     * The upstream answered but kicked the ghost at the application level: that name is already
     * online there. Either a previous ghost session is still lingering, or the bridge is pointed
     * at the very server it protects (the ghost's name is an online player there by definition).
     */
    private static boolean duplicateSession(String seen) {
        return seen.contains("already playing")
                || seen.contains("already connected")
                || seen.contains("already online")
                || seen.contains("duplicate");
    }

    /**
     * The ghost got as far as the upstream's login chatter (chat, dialog, packets) but the
     * worker never saw the login accepted.
     */
    private static boolean reachedUpstreamPrompt(String seen) {
        if (!seen.contains("last observed")) {
            return false;
        }
        return seen.contains("[chat]")
                || seen.contains("dialog")
                || seen.contains("packet")
                || seen.contains("connected to");
    }

    private static Hint duplicateSessionHint() {
        return new Hint("duplicate-session", List.of(
                "the upstream answered but refused the login: that name is already online there."
                        + " Usually a previous ghost session is still lingering upstream - wait about"
                        + " a minute before retrying, rapid retries re-trigger it (ask the upstream"
                        + " admin to kick the ghost if it persists)",
                "if EVERY login ends this way even right after a restart, the bridge is pointed at"
                        + " this server itself: set bridge.upstream-endpoints to the separate auth"
                        + " server's internal address, never this server's public address/port"));
    }

    private static Hint unreachableHint(List<String> endpoints, String listeningPorts) {
        String tried = endpoints.isEmpty() ? "the configured endpoint" : String.join(", ", endpoints);
        String first = firstEndpoint(endpoints);
        var lines = new java.util.ArrayList<String>();
        lines.add("the worker could not reach the upstream (tried " + tried + ")"
                + " - run /loginguard probe to test each endpoint and see which ports this"
                + " machine listens on (" + listeningPorts + ")");
        // The exact trap in the snow.playwithbao.com:50252 report: both the public
        // address and its 127.0.0.1 twin were refused. A published port is the one
        // players join on, not the port anything listens on inside the container, so
        // when the configured endpoint IS this server's own join address the bridge is
        // pointed at itself and can never answer - it must name the separate auth
        // server's internal address in bridge.upstream-endpoints instead.
        if (!first.isBlank()) {
            lines.add("if " + first + " is how players join THIS server, the bridge is"
                    + " pointed at itself: set bridge.upstream-endpoints to the auth server's"
                    + " real internal address (e.g. 172.x.x.x:25565 or 127.0.0.1:25565 when"
                    + " co-hosted), never this server's public address/port");
        } else {
            lines.add("a box's own published port is usually refused from inside it, so a"
                    + " container needs the upstream's internal address in"
                    + " bridge.upstream-endpoints");
        }
        return new Hint("unreachable", List.copyOf(lines));
    }

    private static Hint notAcceptedHint(String first) {
        String where = first.isBlank() ? "the upstream" : first;
        return new Hint("not-accepted", List.of(
                "the ghost reached the upstream but its login was never accepted (see the 'last"
                        + " observed' detail above) - the password probably does not match the"
                        + " upstream account, or the upstream demands a step the bridge cannot"
                        + " complete (captcha, email code, dialog)",
                "check by joining " + where + " directly in the game with the same password: if"
                        + " that login also fails the passwords differ; if it asks for an extra"
                        + " step, the bridge cannot complete it"));
    }

    private static String firstEndpoint(List<String> endpoints) {
        return endpoints.isEmpty() ? "" : endpoints.get(0);
    }
}
