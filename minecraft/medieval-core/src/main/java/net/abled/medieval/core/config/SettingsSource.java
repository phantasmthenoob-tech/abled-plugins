package net.abled.medieval.core.config;

/**
 * Minimal read-only view over a configuration backend.
 *
 * <p>Deliberately tiny so the core stays free of any server types and so tests can supply
 * in-memory values.
 */
public interface SettingsSource {

    boolean getBoolean(String path, boolean fallback);

    int getInt(String path, int fallback);

    long getLong(String path, long fallback);

    String getString(String path, String fallback);

    /** True when the path is present in the underlying configuration. */
    boolean has(String path);
}
