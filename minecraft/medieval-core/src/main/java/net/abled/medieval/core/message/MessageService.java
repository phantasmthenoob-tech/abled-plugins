package net.abled.medieval.core.message;

import net.abled.medieval.api.MedievalService;

import java.util.Map;
import java.util.Objects;

/**
 * Resolves message templates and substitutes {@code {token}} placeholders.
 *
 * <p>Templates stay in MiniMessage form and are only deserialized by the platform layer, so the
 * core never depends on an Adventure version. A reload swaps the source atomically.
 */
public final class MessageService implements MedievalService {

    private final String missingTemplate;
    private volatile MessageSource source;

    public MessageService(MessageSource source) {
        this(source, "<red>Missing message: {key}");
    }

    public MessageService(MessageSource source, String missingTemplate) {
        this.source = Objects.requireNonNull(source, "source");
        this.missingTemplate = Objects.requireNonNull(missingTemplate, "missingTemplate");
    }

    @Override
    public String name() {
        return "messages";
    }

    /** Swaps in a freshly loaded source; safe to call from {@code /medieval reload}. */
    public void reload(MessageSource replacement) {
        this.source = Objects.requireNonNull(replacement, "replacement");
    }

    /** Raw template with placeholders substituted, ready for MiniMessage deserialization. */
    public String raw(String key, Map<String, String> placeholders) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(placeholders, "placeholders");

        String template = source.template(key).orElseGet(() -> missingTemplate.replace("{key}", key));
        if (placeholders.isEmpty()) {
            return template;
        }

        String resolved = template;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            resolved = resolved.replace("{" + placeholder.getKey() + "}", placeholder.getValue());
        }
        return resolved;
    }

    public String raw(String key) {
        return raw(key, Map.of());
    }

    public String prefix() {
        return raw("prefix");
    }

    public String noPermission() {
        return raw("no-permission");
    }
}
