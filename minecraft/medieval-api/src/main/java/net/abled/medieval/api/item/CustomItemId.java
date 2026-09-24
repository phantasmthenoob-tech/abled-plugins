package net.abled.medieval.api.item;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable server-side identity of a custom item, for example {@code medieval:longsword}.
 *
 * <p>Gameplay state is always keyed on this identifier — never on an item's display name,
 * lore or model — so the Java resource pack and the Bedrock pack are presentation only.
 */
public final class CustomItemId {

    /** Namespace reserved for Medieval content. */
    public static final String NAMESPACE = "medieval";

    private static final Pattern VALID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final String key;
    private final String namespace;
    private final String path;

    private CustomItemId(String key, String namespace, String path) {
        this.key = key;
        this.namespace = namespace;
        this.path = path;
    }

    public static CustomItemId of(String key) {
        Objects.requireNonNull(key, "key");
        if (!VALID.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid custom item id: '" + key + "'");
        }
        int separator = key.indexOf(':');
        return new CustomItemId(key, key.substring(0, separator), key.substring(separator + 1));
    }

    public static CustomItemId of(String namespace, String path) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        return of(namespace + ":" + path);
    }

    /** Convenience factory for content in the {@code medieval} namespace. */
    public static CustomItemId medieval(String path) {
        return of(NAMESPACE, path);
    }

    /** Full namespaced key, for example {@code medieval:longsword}. */
    public String key() {
        return key;
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CustomItemId that && key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
