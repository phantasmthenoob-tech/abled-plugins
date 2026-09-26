package net.abled.medieval.paper.cmdblock;

import net.abled.medieval.core.cmdblock.WandMode;
import net.abled.medieval.core.cmdblock.WandTrigger;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds command wands, and recognises one in a player's hand.
 *
 * <h2>How a wand is "that certain one"</h2>
 * A wand is an ordinary fishing rod or carrot/warped-fungus-on-a-stick whose item carries a
 * PersistentDataContainer entry with a UUID: {@link #KEY_ID}. Every right-click handler compares
 * the clicked item's stored UUID against the wands the plugin knows about, so only that exact item
 * activates - a vanilla rod, or a second copy of the same rod without the mark, is ignored. The
 * mark lives in the item data, so it survives storage, drop-and-pickup, chest moves and restarts,
 * and it cannot be forged by renaming, because the check never looks at the display name.
 *
 * <p>The mode is stored alongside the id ({@link #KEY_MODE}, a byte) so a single factory method
 * can rebuild the display lore from the item alone.
 *
 * <h2>Materials</h2>
 * Fishing rod, carrot on a stick and warped fungus on a stick: the three items the ask named and
 * anything the vanilla game treats as a "stick" vehicle tool. A wand is unbreakable and keeps its
 * glint, so it reads as a tool rather than a fishing rod somebody should cast.
 */
public final class CommandWandFactory {

    /** The wand's identity: the UUID the listener matches a click against. */
    public final NamespacedKey keyId;
    /** The wand's mode as a byte, so the lore can be rebuilt without a lookup. */
    public final NamespacedKey keyMode;
    /** The wand's trigger as a byte, alongside the mode. */
    public final NamespacedKey keyTrigger;

    private final MessageRenderer renderer;

    public CommandWandFactory(Plugin plugin, MessageRenderer renderer) {
        this.keyId = new NamespacedKey(plugin, "wand_id");
        this.keyMode = new NamespacedKey(plugin, "wand_mode");
        this.keyTrigger = new NamespacedKey(plugin, "wand_trigger");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /**
     * Builds one wand of the given material.
     *
     * @return the finished item, ready to hand out
     */
    public ItemStack build(UUID id, WandMode.Mode mode, WandTrigger.Trigger trigger,
                           Material material, String command) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("cannot hold item data: " + material);
        }

        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(keyId, PersistentDataType.STRING, id.toString());
        data.set(keyMode, PersistentDataType.BYTE, (byte) mode.ordinal());
        data.set(keyTrigger, PersistentDataType.BYTE, (byte) trigger.ordinal());

        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(renderer.render(wandLabelKey(mode), Map.of()));
        meta.lore(renderer.renderLines("cmdblock-lore", Map.of(
                "mode", WandMode.displayName(mode),
                "trigger", WandTrigger.displayName(trigger),
                "command", command.length() > 40 ? command.substring(0, 37) + "..." : command)));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Rewrites an existing wand item's mode and trigger in place.
     *
     * <p>The identity is left alone, so the item is still the same wand the service knows; only
     * the label, lore and mode byte change. Called when the owner switches a held wand's mode or
     * trigger without making a new item.
     *
     * @return true when the item was a wand and was updated; false for a non-wand item
     */
    public boolean refresh(ItemStack item, WandMode.Mode mode, WandTrigger.Trigger trigger, String command) {
        if (item == null || !item.hasItemMeta() || idOf(item).isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keyMode, PersistentDataType.BYTE, (byte) mode.ordinal());
        meta.getPersistentDataContainer().set(keyTrigger, PersistentDataType.BYTE, (byte) trigger.ordinal());
        meta.displayName(renderer.render(wandLabelKey(mode), Map.of()));
        meta.lore(renderer.renderLines("cmdblock-lore", Map.of(
                "mode", WandMode.displayName(mode),
                "trigger", WandTrigger.displayName(trigger),
                "command", command.length() > 40 ? command.substring(0, 37) + "..." : command)));
        item.setItemMeta(meta);
        return true;
    }

    /**
     * The wand identity of an item, or empty when the item is not a wand.
     *
     * @param item the clicked or held item; may be null, as event hands can be
     */
    public Optional<UUID> idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(keyId, PersistentDataType.STRING);
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(stored));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** The mode recorded on a wand item, defaulting to impulse when the byte is missing. */
    public WandMode.Mode modeOf(ItemStack item) {
        Byte ordinal = wandByte(item, keyMode);
        WandMode.Mode[] modes = WandMode.Mode.values();
        if (ordinal == null || ordinal < 0 || ordinal >= modes.length) {
            return WandMode.Mode.IMPULSE;
        }
        return modes[ordinal];
    }

    /** The trigger recorded on a wand item, defaulting to needs-redstone when the byte is missing. */
    public WandTrigger.Trigger triggerOf(ItemStack item) {
        Byte ordinal = wandByte(item, keyTrigger);
        WandTrigger.Trigger[] triggers = WandTrigger.Trigger.values();
        if (ordinal == null || ordinal < 0 || ordinal >= triggers.length) {
            return WandTrigger.Trigger.CLICK;
        }
        return triggers[ordinal];
    }

    private static Byte wandByte(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
    }

    /** Whether this material can become a wand at all. */
    public static boolean isWandMaterial(Material material) {
        return material == Material.FISHING_ROD
                || material == Material.CARROT_ON_A_STICK
                || material == Material.WARPED_FUNGUS_ON_A_STICK;
    }

    private static String wandLabelKey(WandMode.Mode mode) {
        return switch (mode) {
            case IMPULSE -> "cmdblock-name-impulse";
            case REPEATING -> "cmdblock-name-repeating";
            case CHAIN -> "cmdblock-name-chain";
        };
    }
}
