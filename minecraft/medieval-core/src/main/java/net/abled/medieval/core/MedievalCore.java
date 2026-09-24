package net.abled.medieval.core;

import net.abled.medieval.api.EventBus;
import net.abled.medieval.api.MedievalPlatform;
import net.abled.medieval.api.MedievalService;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.event.SimpleEventBus;
import net.abled.medieval.core.service.ServiceRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Version-independent container for the Medieval system.
 *
 * <p>Holds the platform bridge, the current settings snapshot, the service registry and the
 * event bus. Nothing in this class (or anything it references) knows about Bukkit, so the same
 * core can run behind a different platform adapter later without changes.
 */
public final class MedievalCore {

    private final MedievalPlatform platform;
    private final ServiceRegistry services = new ServiceRegistry();
    private final SimpleEventBus eventBus;
    private final List<String> startupNotes = new ArrayList<>();

    private MedievalSettings settings;
    private boolean enabled;

    public MedievalCore(MedievalPlatform platform, MedievalSettings settings) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.eventBus = new SimpleEventBus(failure -> platform.logError("A Medieval event listener failed", failure));
    }

    public MedievalPlatform platform() {
        return platform;
    }

    /** Current settings snapshot; always re-read this instead of caching it in a service. */
    public MedievalSettings settings() {
        return settings;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public ServiceRegistry services() {
        return services;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Notes recorded while loading, printed once at startup. */
    public List<String> startupNotes() {
        return List.copyOf(startupNotes);
    }

    public void note(String message) {
        startupNotes.add(Objects.requireNonNull(message, "message"));
    }

    /** Applies a freshly loaded settings snapshot without restarting the server. */
    public void applySettings(MedievalSettings updated) {
        this.settings = Objects.requireNonNull(updated, "updated");
    }

    public void enable() {
        if (enabled) {
            return;
        }
        enabled = true;
        for (MedievalService service : services.all()) {
            try {
                service.onEnable();
            } catch (RuntimeException failure) {
                platform.logError("Failed to enable service '" + service.name() + "'", failure);
            }
        }
    }

    public void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        List<MedievalService> ordered = new ArrayList<>(services.all());
        Collections.reverse(ordered);
        for (MedievalService service : ordered) {
            try {
                service.onDisable();
            } catch (RuntimeException failure) {
                platform.logError("Failed to disable service '" + service.name() + "'", failure);
            }
        }
    }
}
