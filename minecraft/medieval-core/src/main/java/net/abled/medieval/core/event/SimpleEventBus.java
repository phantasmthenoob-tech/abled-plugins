package net.abled.medieval.core.event;

import net.abled.medieval.api.EventBus;
import net.abled.medieval.api.MedievalEvent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Synchronous, thread-safe event bus.
 *
 * <p>Events are delivered on the publishing thread. A listener that throws does not prevent the
 * remaining listeners from running; failures are reported to the configured error handler so a
 * broken subsystem cannot silently stop gameplay updates.
 */
public final class SimpleEventBus implements EventBus {

    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners = new ConcurrentHashMap<>();
    private final Consumer<Throwable> errorHandler;

    public SimpleEventBus(Consumer<Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    @Override
    public <T extends MedievalEvent> Subscription subscribe(Class<T> eventType, Consumer<T> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");

        CopyOnWriteArrayList<Consumer<?>> registered =
                listeners.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>());
        registered.add(listener);
        return () -> registered.remove(listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends MedievalEvent> void publish(T event) {
        Objects.requireNonNull(event, "event");

        for (Map.Entry<Class<?>, CopyOnWriteArrayList<Consumer<?>>> entry : listeners.entrySet()) {
            if (!entry.getKey().isInstance(event)) {
                continue;
            }
            for (Consumer<?> listener : entry.getValue()) {
                try {
                    ((Consumer<T>) listener).accept(event);
                } catch (RuntimeException failure) {
                    errorHandler.accept(failure);
                }
            }
        }
    }

    /** Number of registered listeners across all event types; used by diagnostics and tests. */
    public int listenerCount() {
        int total = 0;
        for (CopyOnWriteArrayList<Consumer<?>> registered : listeners.values()) {
            total += registered.size();
        }
        return total;
    }
}
