package net.abled.medieval.core.cmdblock;

import java.util.UUID;

/**
 * One command wand binding: what a marked item runs, and how.
 *
 * <p>The record is the storage shape, so it lives in the core next to the store that reads and
 * writes it. The identity is the key, not a field: it is the UUID inside the item's
 * PersistentDataContainer, and the store is keyed by it.
 */
public record WandBinding(WandMode.Mode mode, WandTrigger.Trigger trigger, String command) {

    public WandBinding {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("trigger must not be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
    }

    /** Convenience constructor for wands made through the command, which start needs-redstone. */
    public static WandBinding clickWand(WandMode.Mode mode, String command) {
        return new WandBinding(mode, WandTrigger.Trigger.CLICK, command);
    }

    /** The storage key for a wand: its item's PersistentDataContainer UUID. */
    public static UUID keyOf(String raw) {
        return UUID.fromString(raw);
    }

    /** The UUID as the store writes it. */
    public static String rawKey(UUID id) {
        return id.toString();
    }
}
