package net.abled.loginguard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The October ableduniversesmp report: a ghost kicked for "already playing" got the
 * refused-port hint although the upstream had answered, and a ghost stuck at the AuthMe
 * prompt timed out with no hint at all. Bukkit-free like ProbeArgs: paper-api is compileOnly.
 */
class UpstreamDiagnosisTest {

    private static final List<String> ENDPOINTS =
            List.of("ableduniversesmp.playwithbao.com:13069", "127.0.0.1:13069");

    @Test
    void duplicateSessionDownNamesTheLingeringGhost() {
        Optional<UpstreamDiagnosis.Hint> hint = UpstreamDiagnosis.diagnose(Verdict.UPSTREAM_DOWN,
                "UPSTREAM_DOWN, last observed: [disconnect] The same username is already playing"
                        + " on the server!",
                ENDPOINTS, "4519, 25625");
        assertTrue(hint.isPresent());
        assertEquals("duplicate-session", hint.get().key());
        String joined = String.join("\n", hint.get().lines());
        assertTrue(joined.contains("already online there"));
        assertTrue(joined.contains("wait about a minute"));
    }

    @Test
    void duplicateSessionDownNeverClaimsTheUpstreamWasUnreachable() {
        Optional<UpstreamDiagnosis.Hint> hint = UpstreamDiagnosis.diagnose(Verdict.UPSTREAM_DOWN,
                "already connected to the server", ENDPOINTS, "4519");
        assertTrue(hint.isPresent());
        for (String line : hint.get().lines()) {
            assertTrue(!line.contains("could not reach"),
                    "must not report refused when the upstream answered: " + line);
        }
    }

    @Test
    void refusedDownKeepsTheUnreachableHint() {
        Optional<UpstreamDiagnosis.Hint> hint = UpstreamDiagnosis.diagnose(Verdict.UPSTREAM_DOWN,
                "connection refused", ENDPOINTS, "4519, 25625");
        assertTrue(hint.isPresent());
        assertEquals("unreachable", hint.get().key());
        String joined = String.join("\n", hint.get().lines());
        assertTrue(joined.contains("ableduniversesmp.playwithbao.com:13069, 127.0.0.1:13069"));
        assertTrue(joined.contains("/loginguard probe"));
    }

    @Test
    void blankDetailDownStaysUnreachable() {
        Optional<UpstreamDiagnosis.Hint> hint =
                UpstreamDiagnosis.diagnose(Verdict.UPSTREAM_DOWN, "", ENDPOINTS, "4519");
        assertTrue(hint.isPresent());
        assertEquals("unreachable", hint.get().key());
    }

    @Test
    void timeoutAtThePromptSuggestsThePasswordCheck() {
        Optional<UpstreamDiagnosis.Hint> hint = UpstreamDiagnosis.diagnose(Verdict.UPSTREAM_TIMEOUT,
                "UPSTREAM_TIMEOUT, last observed: [chat] Please, login with the command: /login <password>",
                ENDPOINTS, "4519");
        assertTrue(hint.isPresent());
        assertEquals("not-accepted", hint.get().key());
        String joined = String.join("\n", hint.get().lines());
        assertTrue(joined.contains("never accepted"));
        assertTrue(joined.contains("ableduniversesmp.playwithbao.com:13069"));
    }

    @Test
    void quietTimeoutStaysSilent() {
        assertEquals(Optional.empty(), UpstreamDiagnosis.diagnose(
                Verdict.UPSTREAM_TIMEOUT, "", ENDPOINTS, "4519"));
        assertEquals(Optional.empty(), UpstreamDiagnosis.diagnose(
                Verdict.UPSTREAM_TIMEOUT, "UPSTREAM_TIMEOUT", ENDPOINTS, "4519"));
    }

    @Test
    void decisiveVerdictsStaySilent() {
        for (Verdict verdict : List.of(Verdict.SUCCESS, Verdict.WRONG_PASSWORD, Verdict.NO_ACCOUNT)) {
            assertEquals(Optional.empty(), UpstreamDiagnosis.diagnose(
                    verdict, "UPSTREAM_DOWN, last observed: [disconnect] already playing", ENDPOINTS,
                    "4519"));
        }
    }
}
