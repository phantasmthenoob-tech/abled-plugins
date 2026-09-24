package net.abled.medieval.core.service;

import net.abled.medieval.api.MedievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceRegistryTest {

    private record TestService(String name) implements MedievalService {
    }

    private interface Named extends MedievalService {
    }

    private final ServiceRegistry registry = new ServiceRegistry();

    @Test
    void resolvesRegisteredServices() {
        TestService service = new TestService("kingdoms");
        registry.register(TestService.class, service);

        assertSame(service, registry.require(TestService.class));
        assertTrue(registry.find(TestService.class).isPresent());
        assertTrue(registry.isRegistered(TestService.class));
        assertEquals(1, registry.size());
    }

    @Test
    void rejectsDuplicateRegistrations() {
        registry.register(TestService.class, new TestService("first"));

        assertThrows(IllegalStateException.class,
                () -> registry.register(TestService.class, new TestService("second")));
    }

    @Test
    void failsFastWhenAServiceIsMissing() {
        assertTrue(registry.find(TestService.class).isEmpty());
        assertFalse(registry.isRegistered(TestService.class));
        assertThrows(IllegalStateException.class, () -> registry.require(TestService.class));
    }

    @Test
    void keepsRegistrationOrderForLifecycleCalls() {
        assertEquals(List.of(), registry.all());

        registry.register(TestService.class, new TestService("first"));
        registry.register(Named.class, () -> "second");

        assertEquals(List.of("first", "second"), registry.all().stream().map(MedievalService::name).toList());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> registry.register(null, new TestService("x")));
        assertThrows(NullPointerException.class, () -> registry.register(TestService.class, null));
    }
}
