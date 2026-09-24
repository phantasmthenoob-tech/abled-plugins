package net.abled.medieval.core;

import net.abled.medieval.api.MedievalEvent;
import net.abled.medieval.api.MedievalPlatform;
import net.abled.medieval.api.MedievalService;
import net.abled.medieval.core.config.MedievalSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MedievalCoreTest {

    private record Created(String name) implements MedievalEvent {
    }

    /** Records lifecycle order and captures log output for assertions. */
    private static final class RecordingPlatform implements MedievalPlatform {

        private final List<String> lifecycle = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public String serverName() {
            return "TestServer";
        }

        @Override
        public String serverVersion() {
            return "test-0.0.1";
        }

        @Override
        public boolean isPluginPresent(String pluginName) {
            return false;
        }

        @Override
        public boolean isBedrockPlayer(UUID playerId) {
            return false;
        }

        @Override
        public void logInfo(String message) {
        }

        @Override
        public void logWarning(String message) {
        }

        @Override
        public void logError(String message, Throwable cause) {
            errors.add(message);
        }
    }

    private record LifecycleService(String name, List<String> sink) implements MedievalService {

        @Override
        public void onEnable() {
            sink.add("enable:" + name);
        }

        @Override
        public void onDisable() {
            sink.add("disable:" + name);
        }
    }

    private static MedievalSettings settings() {
        return new MedievalSettings(
                new MedievalSettings.Deathban(true, Duration.ofHours(1)),
                new MedievalSettings.Dimensions(false, false),
                new MedievalSettings.Siege(true,
                        new MedievalSettings.Siege.Machine(1000, 100, Duration.ofSeconds(4)),
                        new MedievalSettings.Siege.Machine(750, 150, Duration.ofSeconds(6))),
                new MedievalSettings.Territory(25, true));
    }

    @Test
    void startsServicesInOrderAndStopsThemInReverse() {
        RecordingPlatform platform = new RecordingPlatform();
        MedievalCore core = new MedievalCore(platform, settings());
        core.services().register(LifecycleService.class, new LifecycleService("kingdom", platform.lifecycle));
        core.services().register(MedievalService.class, new LifecycleService("siege", platform.lifecycle));

        core.enable();
        assertTrue(core.isEnabled());
        core.disable();

        assertEquals(List.of("enable:kingdom", "enable:siege", "disable:siege", "disable:kingdom"),
                platform.lifecycle);
        assertFalse(core.isEnabled());
    }

    @Test
    void enableAndDisableAreIdempotent() {
        RecordingPlatform platform = new RecordingPlatform();
        MedievalCore core = new MedievalCore(platform, settings());
        core.services().register(LifecycleService.class, new LifecycleService("siege", platform.lifecycle));

        core.enable();
        core.enable();
        core.disable();
        core.disable();

        assertEquals(List.of("enable:siege", "disable:siege"), platform.lifecycle);
    }

    @Test
    void failingServiceDoesNotBlockStartup() {
        RecordingPlatform platform = new RecordingPlatform();
        MedievalCore core = new MedievalCore(platform, settings());
        core.services().register(MedievalService.class, new MedievalService() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void onEnable() {
                throw new IllegalStateException("boom");
            }
        });
        core.services().register(LifecycleService.class, new LifecycleService("siege", platform.lifecycle));

        core.enable();

        assertEquals(List.of("enable:siege"), platform.lifecycle);
        assertEquals(1, platform.errors.size());
        assertTrue(platform.errors.get(0).contains("broken"), platform.errors.get(0));
    }

    @Test
    void publishesDomainEventsThroughTheBus() {
        MedievalCore core = new MedievalCore(new RecordingPlatform(), settings());
        List<String> received = new ArrayList<>();
        core.eventBus().subscribe(Created.class, event -> received.add(event.name()));

        core.eventBus().publish(new Created("Avalon"));

        assertEquals(List.of("Avalon"), received);
    }

    @Test
    void appliesReloadedSettings() {
        MedievalCore core = new MedievalCore(new RecordingPlatform(), settings());
        MedievalSettings updated = new MedievalSettings(
                new MedievalSettings.Deathban(false, Duration.ofMinutes(5)),
                settings().dimensions(),
                settings().siege(),
                settings().territory());

        core.applySettings(updated);

        assertFalse(core.settings().deathban().enabled());
        assertEquals(Duration.ofMinutes(5), core.settings().deathban().duration());
    }

    @Test
    void collectsStartupNotes() {
        MedievalCore core = new MedievalCore(new RecordingPlatform(), settings());

        core.note("Loaded 12 custom items");

        assertEquals(List.of("Loaded 12 custom items"), core.startupNotes());
    }
}
