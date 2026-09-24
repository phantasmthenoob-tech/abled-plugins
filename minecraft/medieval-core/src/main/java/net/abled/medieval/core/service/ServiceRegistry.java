package net.abled.medieval.core.service;

import net.abled.medieval.api.MedievalService;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registration order matters: services start in the order they were registered and stop in
 * reverse order, so a service can depend on an earlier one being available.
 */
public final class ServiceRegistry {

    private final Map<Class<?>, MedievalService> services = new LinkedHashMap<>();

    public <T extends MedievalService> void register(Class<T> type, T service) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        MedievalService existing = services.putIfAbsent(type, service);
        if (existing != null) {
            throw new IllegalStateException("a service is already registered for " + type.getName());
        }
    }

    public <T extends MedievalService> T require(Class<T> type) {
        Objects.requireNonNull(type, "type");
        MedievalService service = services.get(type);
        if (service == null) {
            throw new IllegalStateException("no service registered for " + type.getName());
        }
        return type.cast(service);
    }

    public <T extends MedievalService> Optional<T> find(Class<T> type) {
        Objects.requireNonNull(type, "type");
        MedievalService service = services.get(type);
        return service == null ? Optional.empty() : Optional.of(type.cast(service));
    }

    public boolean isRegistered(Class<? extends MedievalService> type) {
        return services.containsKey(type);
    }

    /** Registered services in start order. */
    public Collection<MedievalService> all() {
        return List.copyOf(services.values());
    }

    public int size() {
        return services.size();
    }
}
