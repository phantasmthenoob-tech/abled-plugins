package net.abled.medieval.core.event;

import net.abled.medieval.api.EventBus;
import net.abled.medieval.api.MedievalEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleEventBusTest {

    private record KingdomCreated(String name) implements MedievalEvent {
    }

    private record SiegeStarted(String kingdom) implements MedievalEvent {
    }

    private record Unrelated(String value) implements MedievalEvent {
    }

    private final List<Throwable> failures = new ArrayList<>();
    private final SimpleEventBus bus = new SimpleEventBus(failures::add);

    @Test
    void deliversToMatchingType() {
        List<String> received = new ArrayList<>();
        bus.subscribe(KingdomCreated.class, event -> received.add(event.name()));

        bus.publish(new KingdomCreated("Avalon"));

        assertEquals(List.of("Avalon"), received);
    }

    @Test
    void doesNotDeliverToUnrelatedTypes() {
        List<String> received = new ArrayList<>();
        bus.subscribe(SiegeStarted.class, event -> received.add(event.kingdom()));

        bus.publish(new KingdomCreated("Avalon"));

        assertEquals(List.of(), received);
    }

    @Test
    void deliversToSupertypeSubscribers() {
        AtomicInteger count = new AtomicInteger();
        bus.subscribe(MedievalEvent.class, event -> count.incrementAndGet());

        bus.publish(new KingdomCreated("Avalon"));
        bus.publish(new SiegeStarted("Avalon"));
        bus.publish(new Unrelated("value"));

        assertEquals(3, count.get());
    }

    @Test
    void subscriptionCanBeClosed() {
        AtomicInteger count = new AtomicInteger();
        EventBus.Subscription subscription = bus.subscribe(KingdomCreated.class, event -> count.incrementAndGet());

        bus.publish(new KingdomCreated("Avalon"));
        subscription.close();
        bus.publish(new KingdomCreated("Camelot"));

        assertEquals(1, count.get());
        assertEquals(0, bus.listenerCount());
    }

    @Test
    void failingListenerDoesNotStopOtherListeners() {
        List<String> received = new ArrayList<>();
        bus.subscribe(KingdomCreated.class, event -> {
            throw new IllegalStateException("listener blew up");
        });
        bus.subscribe(KingdomCreated.class, event -> received.add(event.name()));

        bus.publish(new KingdomCreated("Avalon"));

        assertEquals(List.of("Avalon"), received);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof IllegalStateException);
    }

    @Test
    void countsRegisteredListeners() {
        bus.subscribe(KingdomCreated.class, event -> {
        });
        bus.subscribe(MedievalEvent.class, event -> {
        });

        assertEquals(2, bus.listenerCount());
    }
}
