package net.abled.medieval.api;

import java.util.function.Consumer;

/**
 * Minimal synchronous event bus shared by core subsystems.
 *
 * <p>Games logic publishes domain events (kingdom created, siege started, ...) here; GUIs,
 * statistics and notifications subscribe instead of being called directly.
 */
public interface EventBus {

    /**
     * Subscribes to an event type. A listener receives every event whose type is assignable to
     * {@code eventType}, so subscribing to {@link MedievalEvent} receives all events.
     */
    <T extends MedievalEvent> Subscription subscribe(Class<T> eventType, Consumer<T> listener);

    <T extends MedievalEvent> void publish(T event);

    @FunctionalInterface
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}
