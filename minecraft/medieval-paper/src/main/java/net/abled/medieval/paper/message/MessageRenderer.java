package net.abled.medieval.paper.message;

import net.abled.medieval.core.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

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
