package net.abled.medieval.paper.message;

import net.abled.medieval.core.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns core message templates into components.
 *
 * <p>The core owns the text (MiniMessage source plus {@code {placeholder}} substitution) and knows
 * nothing about Adventure; this class is the single place that deserialises it, so every message in
 * the plugin is rendered the same way and a template edit needs no code change.
 */
public final class MessageRenderer {

    private final MessageService messages;

    public MessageRenderer(MessageService messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** Renders a template without the server prefix, for list-style lines. */
    public Component render(String key, Map<String, String> placeholders) {
        return MiniMessage.miniMessage().deserialize(messages.raw(key, placeholders));
    }

    /** Renders a template behind the {@code [Medieval]} prefix. */
    public Component renderWithPrefix(String key, Map<String, String> placeholders) {
        return MiniMessage.miniMessage().deserialize(messages.prefix() + messages.raw(key, placeholders));
    }

    public Component render(String key) {
        return render(key, Map.of());
    }

    /**
     * Renders a template into one component per line, for item lore.
     *
     * <p>A lore block is a multi-line value in {@code messages.yml} (a YAML block scalar), which the
     * core message service already returns with its newlines intact. Splitting here means hover text
     * needs no list support in the configuration model and stays editable with the same
     * {@code {placeholder}} rules as every other message. An empty or missing template yields no
     * lines rather than a blank lore line.
     */
    public List<Component> renderLines(String key, Map<String, String> placeholders) {
        return messages.raw(key, placeholders).lines()
                .map(line -> MiniMessage.miniMessage().deserialize(line))
                .toList();
    }

    public List<Component> renderLines(String key) {
        return renderLines(key, Map.of());
    }

    public Component renderWithPrefix(String key) {
        return renderWithPrefix(key, Map.of());
    }

    /** Sends one rendered line to any sender, including the console and command blocks. */
    public void send(CommandSender sender, String key, Map<String, String> placeholders, boolean withPrefix) {
        Objects.requireNonNull(sender, "sender");
        sender.sendMessage(withPrefix ? renderWithPrefix(key, placeholders) : render(key, placeholders));
    }

    public void send(CommandSender sender, String key, boolean withPrefix) {
        send(sender, key, Map.of(), withPrefix);
    }
}
