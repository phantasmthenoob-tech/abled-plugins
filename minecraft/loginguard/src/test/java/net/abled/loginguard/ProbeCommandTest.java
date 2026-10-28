package net.abled.loginguard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The documented form is "/loginguard probe [host:port]" - the literal word must not be probed. */
class ProbeCommandTest {

    // ProbeArgs, not ProbeCommand: paper-api is compileOnly, so anything touching Bukkit types
    // cannot compile on the test classpath.

    @Test
    void stripsTheDocumentedProbeWord() {
        assertEquals(List.of("twilightsmp.mcplay.fun:25565"),
                ProbeArgs.commandWords(new String[]{"probe", "twilightsmp.mcplay.fun:25565"}));
    }

    @Test
    void bareProbeAloneProbesTheConfiguredEndpoints() {
        assertEquals(List.of(), ProbeArgs.commandWords(new String[]{"probe"}));
    }

    @Test
    void bareEndpointWithoutProbeStillWorks() {
        assertEquals(List.of("twilightsmp.mcplay.fun:25565"),
                ProbeArgs.commandWords(new String[]{"twilightsmp.mcplay.fun:25565"}));
    }

    @Test
    void probeWordIsCaseInsensitive() {
        assertEquals(List.of("host:25565"),
                ProbeArgs.commandWords(new String[]{"PROBE", "host:25565"}));
    }

    @Test
    void extraWordsSurviveForTheUsageError() {
        assertEquals(List.of("a", "b"), ProbeArgs.commandWords(new String[]{"probe", "a", "b"}));
    }
}
