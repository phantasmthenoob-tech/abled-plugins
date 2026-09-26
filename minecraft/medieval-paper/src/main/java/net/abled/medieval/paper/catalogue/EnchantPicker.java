package net.abled.medieval.paper.catalogue;

import net.abled.medieval.core.catalogue.CataloguePage;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The second GUI of the owner catalogue: pick an enchantment, set its level, walk out with the item.
 *
 * <h2>Flow</h2>
 * Armed from a clicked item in the main catalogue ({@code startFrom}), the picker lists every
 * enchantment the registry holds as its own button, and shows a live preview of the draft. Clicking
 * an enchantment closes the picker and asks for the level in chat (the same capture the search uses,
 * and the only text input Geyser carries); the answer is clamped to the enchantment's real range and
 * re-opens the picker with the preview updated. Repeat for every enchantment, then press Done to
 * receive the finished item. Clear empties the draft in place. Back returns to the main catalogue
 * without giving anything.
 *
 * <h2>Why chat for levels</h2>
 * Same rule as the search: the sign editor and anvil renaming are exactly the screens Geyser does
 * not carry to Bedrock, and the level buttons cycle 1→2→3 only. A typed answer accepts
 * {@code 2}, {@code " II"} and {@code five} alike, so the step costs one line either way.
 *
 * <h2>Bedrock</h2>
 * Every action is a distinct slot; no left/right distinction, no cursor item, no shift rules. The
 * preview occupies the last row's centre so it cannot be confused with an enchantment button, and
 * like every catalogue view, all clicks and drags are cancelled before they are interpreted.
 *
 * <h2>Why a custom {@link InventoryHolder}</h2>
 * Same reason as the main menu: the holder is this object, so the listener recognises its own
 * inventory, and the draft, page and button row travel with the inventory rather than a map keyed
 * by UUID.
 */
public final class EnchantPicker implements InventoryHolder {

    public static final int SIZE = 54;

    /** Slots 0-44 hold enchantment buttons (45 per page).
     *  Row 6: back | previous | clear | - | preview | - | done | - | next. */
    private static final int CONTENT_SLOTS = 45;

    public static final int SLOT_BACK = 45;
    public static final int SLOT_PREVIOUS = 46;
    public static final int SLOT_CLEAR = 47;
    public static final int SLOT_PREVIEW = 49;
    public static final int SLOT_DONE = 51;
    public static final int SLOT_NEXT = 53;

    private static final String MESSAGE_TITLE = "enchant-title";

    private final CatalogueIndex index;
    private final MessageRenderer renderer;
    private final CatalogueHandout handout;
    private final Inventory inventory;

    private final List<Enchantment> enchantments;
    private CataloguePage page;
    private EnchantDraft draft;
    /** The item the flow started from, remembered for its name in messages. */
    private String origin;

    public EnchantPicker(CatalogueIndex index, MessageRenderer renderer, CatalogueHandout handout) {
        this.index = Objects.requireNonNull(index, "index");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.handout = Objects.requireNonNull(handout, "handout");
        this.inventory = Bukkit.createInventory(this, SIZE, renderer.render(MESSAGE_TITLE));
        this.enchantments = readEnchantments();
        this.page = CataloguePage.of(enchantments.size(), CONTENT_SLOTS, 0);
    }

    /** Draws the current draft and shows the picker. Must run on the tick thread. */
    public void open(Player player, EnchantDraft draft, String origin) {
        this.draft = Objects.requireNonNull(draft, "draft");
        this.origin = origin == null ? Names.of(draft.material()) : origin;
        render();
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The enchantment a content slot currently shows, or empty for padding and past-the-end. */
    public java.util.Optional<Enchantment> content(int slot) {
        int offset = slot;
        if (offset < 0 || offset >= page.size()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(enchantments.get(page.fromInclusive() + offset));
    }

    /** Moves one page in the picker's own listing. */
    public void shift(int delta) {
        page = page.shift(enchantments.size(), CONTENT_SLOTS, delta);
        render();
    }

    /** How many enchantments the picker offers in total. */
    public int total() {
        return enchantments.size();
    }

    /** The item being built. */
    public EnchantDraft draft() {
        return draft;
    }

    /** What the flow started from, for messages: {@code Diamond Sword}. */
    public String origin() {
        return origin;
    }

    /** Empties the draft in place and re-renders. */
    public void clear() {
        draft = EnchantDraft.of(draft.material());
        render();
    }

    /** This draft with the enchantment set, re-rendered. */
    public void apply(Enchantment enchantment, int level) {
        draft = draft.with(enchantment, level);
        render();
    }

    /** This draft without the enchantment, re-rendered. */
    public void remove(Enchantment enchantment) {
        draft = draft.without(enchantment);
        render();
    }

    /**
     * Builds the finished item and hands it out, dropping what does not fit. Must run on the tick
     * thread.
     */
    public void finish(Player player) {
        handout.give(player, draft.build(renderer, 1));
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        inventory.clear();

        for (int offset = 0; offset < page.size(); offset++) {
            inventory.setItem(offset, enchantmentButton(enchantments.get(page.fromInclusive() + offset)));
        }

        inventory.setItem(SLOT_PREVIEW, preview());
        inventory.setItem(SLOT_BACK, button("minecraft:spectral_arrow", "enchant-back", Map.of(), "enchant-back-lore"));
        inventory.setItem(SLOT_CLEAR, button("minecraft:water_bucket", "enchant-clear",
                Map.of("count", Integer.toString(draft.enchantments().size())), "enchant-clear-lore"));
        inventory.setItem(SLOT_DONE, button("minecraft:anvil", "enchant-done", Map.of(), "enchant-done-lore"));

        // Same dimmed-not-missing rule as the main menu's row: the shape never changes.
        inventory.setItem(SLOT_PREVIOUS, page.hasPrevious()
                ? button("minecraft:arrow", "enchant-previous", Map.of(), "enchant-previous-lore")
                : button("minecraft:gray_dye", "enchant-no-previous", Map.of(), "enchant-no-previous-lore"));
        inventory.setItem(SLOT_NEXT, page.hasNext()
                ? button("minecraft:spectral_arrow", "enchant-next", Map.of(), "enchant-next-lore")
                : button("minecraft:light_gray_dye", "enchant-no-next", Map.of(), "enchant-no-next-lore"));
    }

    private ItemStack enchantmentButton(Enchantment enchantment) {
        int level = draft.levelOf(enchantment);
        Map<String, String> placeholders = Map.of(
                "enchantment", Names.prettify(enchantment.getKey().getKey()),
                "max", Integer.toString(enchantment.getMaxLevel()),
                "level", level > 0 ? EnchantDraft.roman(level) : "-");
        ItemStack stack = new ItemStack(level > 0
                ? Material.ENCHANTED_BOOK
                : Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(renderer.render(level > 0 ? "enchant-set" : "enchant-option", placeholders));
            meta.lore(renderer.renderLines("enchant-option-lore", placeholders));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack preview() {
        ItemStack stack = draft.build(renderer, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<String> lines = new ArrayList<>();
            lines.add("Preview");
            lines.addAll(draft.describeLines());
            meta.lore(lines.stream()
                    .map(line -> renderer.render("enchant-preview-line", Map.of("line", line)))
                    .toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack button(String icon, String labelKey, Map<String, String> placeholders, String loreKey) {
        ItemStack stack = new ItemStack(icon(icon));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(renderer.render(labelKey, placeholders));
            meta.lore(renderer.renderLines(loreKey, placeholders));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Material icon(String key) {
        Material material = Material.matchMaterial(key);
        return material == null ? Material.STONE : material;
    }

    /**
     * Every enchantment the server's registry holds, in registry order.
     *
     * <p>Iterating the registry rather than a hardcoded list keeps the picker correct for whatever
     * version runs, and an iterate-and-collect is all the API offers. {@code Registry} implements
     * {@code Iterable}, and a stream needs a spliterator on the same object - so the loop collects
     * into a list first.
     *
     * <p>The static {@code Registry.ENCHANTMENT} constant is deprecated in this API in favour of
     * {@code RegistryAccess}; like {@code Material#getCreativeCategory()} in {@link CatalogueIndex},
     * it is used deliberately and confined to this one method. If it is removed, only this method
     * changes, and the picker degrades to an empty list with the Clear/Done buttons still working.
     */
    @SuppressWarnings("deprecation")
    private static List<Enchantment> readEnchantments() {
        List<Enchantment> found = new ArrayList<>();
        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            found.add(enchantment);
        }
        found.sort((left, right) -> left.getKey().getKey().compareTo(right.getKey().getKey()));
        return List.copyOf(found);
    }
}
