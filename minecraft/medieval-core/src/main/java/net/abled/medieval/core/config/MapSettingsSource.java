package net.abled.medieval.core.config;

import java.util.Map;
import java.util.Objects;

/**
 * In-memory {@link SettingsSource} backed by dotted paths, for example
 * {@code Map.of("deathban.enabled", true, "deathban.duration-seconds", 3600L)}.
 *
 * <p>Used for defaults and by unit tests; the Paper module supplies a YAML-backed source.
 */
public final class MapSettingsSource implements SettingsSource {

    private final Map<String, Object> values;

    public MapSettingsSource(Map<String, Object> values) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    public static MapSettingsSource empty() {
        return new MapSettingsSource(Map.of());
    }

    @Override
    public boolean getBoolean(String path, boolean fallback) {
        Object raw = values.get(path);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text) {
            // Accepts YAML values that were quoted, but rejects anything that is not a real boolean.
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }

    @Override
    public int getInt(String path, int fallback) {
        Object raw = values.get(path);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public long getLong(String path, long fallback) {
        Object raw = values.get(path);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public String getString(String path, String fallback) {
        Object raw = values.get(path);
        return raw == null ? fallback : String.valueOf(raw);
    }

    @Override
    public boolean has(String path) {
        return values.containsKey(path);
    }
}
