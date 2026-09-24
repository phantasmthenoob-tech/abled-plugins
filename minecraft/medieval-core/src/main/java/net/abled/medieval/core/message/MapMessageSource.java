package net.abled.medieval.core.message;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory {@link MessageSource} for defaults and tests. */
public final class MapMessageSource implements MessageSource {

    private final Map<String, String> templates;

    public MapMessageSource(Map<String, String> templates) {
        this.templates = Map.copyOf(Objects.requireNonNull(templates, "templates"));
    }

    public static MapMessageSource empty() {
        return new MapMessageSource(Map.of());
    }

    @Override
    public Optional<String> template(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(templates.get(key));
    }
}
