package net.abled.medieval.paper.catalogue;

import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The item being built by the enchantment picker: a material, and the enchantments chosen so far.
 *
 * <p>An immutable snapshot per operation. The menu renders it; the chat prompts extend it; the
 * preview stays live because every change produces a new draft and re-renders. Nothing here
 * touches an inventory - the draft is data, and the listener decides what a click does with it.
 *
 * <p>Levels outside an enchantment's real range are clamped rather than rejected: the point is an
 * owner tool, and a hand-typed level of {@code 100} means sharpness 100 if the game allows the
 * enchantment to hold it - {@link #apply} keeps whatever the item accepts.
 */
public record EnchantDraft(Material material, Map<Enchantment, Integer> enchantments) {

    public EnchantDraft {
        Objects.requireNonNull(material, "material");
        enchantments = Map.copyOf(enchantments);
    }

    /** A fresh draft for one item, with nothing on it yet. */
    public static EnchantDraft of(Material material) {
        return new EnchantDraft(material, Map.of());
    }

    /** This draft plus one enchantment, replacing any earlier level of it. */
    public EnchantDraft with(Enchantment enchantment, int level) {
        Objects.requireNonNull(enchantment, "enchantment");
        Map<Enchantment, Integer> next = new LinkedHashMap<>(enchantments);
        next.put(enchantment, level);
        return new EnchantDraft(material, next);
    }

    /** This draft without one enchantment. */
    public EnchantDraft without(Enchantment enchantment) {
        if (!enchantments.containsKey(enchantment)) {
            return this;
        }
        Map<Enchantment, Integer> next = new LinkedHashMap<>(enchantments);
        next.remove(enchantment);
        return new EnchantDraft(material, next);
    }

    public int levelOf(Enchantment enchantment) {
        return enchantments.getOrDefault(enchantment, 0);
    }

    public boolean isEmpty() {
        return enchantments.isEmpty();
    }

    /**
     * Builds a clean stack of the draft: the plain item with the chosen enchantments and no menu
     * lore, exactly as an owner {@code /give} would have handed it over.
     */
    public ItemStack build(MessageRenderer renderer, int amount) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        if (enchantments.isEmpty()) {
            return stack;
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        enchantments.forEach((enchantment, level) -> {
            try {
                meta.addEnchant(enchantment, level, true);
            } catch (IllegalArgumentException refused) {
                // The item refuses this enchantment or level; it is simply left off rather than
                // failing the whole handout.
            }
        });
        meta.lore(renderer.renderLines("enchant-finished-lore", Map.of(
                "item", Names.of(material))));
        stack.setItemMeta(meta);
        return stack;
    }

    /** One summary line per enchantment, newest first, for the preview lore. */
    public java.util.List<String> describeLines() {
        return enchantments.entrySet().stream()
                .map(entry -> Names.prettify(entry.getKey().getKey().getKey())
                        + " " + roman(entry.getValue()))
                .toList();
    }

    /** Roman numerals for the levels a lore line shows, arabic past ten. */
    static String roman(int level) {
        if (level < 1) {
            return "0";
        }
        if (level > 10) {
            return Integer.toString(level);
        }
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return numerals[level - 1];
    }
}
