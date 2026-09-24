package net.abled.medieval.paper.catalogue;

import net.abled.medieval.core.catalogue.CatalogueCategory;
import net.abled.medieval.core.catalogue.CataloguePage;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One player's view of the owner catalogue: a chest inventory of items to take.
 *
 * <h2>Layout</h2>
 * <pre>
 *   row 1 (slots  0- 8)  category tabs - one per {@link CatalogueCategory}, in declaration order
 *   rows 2-5 (slots 9-44) up to 36 items, the current page of the current tab
 *   row 6 (slots 45-53)  previous, take amount, page number, next, close
 * </pre>
 *
 * <h2>Why a custom {@link InventoryHolder}</h2>
 * The holder is this object, which is how the click listener recognises its own inventory and
 * reads the page and tab that were on screen when a button was pressed. Storing that state on the
 * inventory instead of in a map keyed by UUID means two menus opened by the same player, or a menu
 * left open across a reload, still answer with their own state.
 *
 * <h2>Bedrock</h2>
 * Nothing here depends on telling a left-click from a right-click, because Geyser cannot: on
 * Bedrock every tap arrives as {@code LEFT}. A tap therefore takes the selected amount, and the
 * Take amount button (1/8/16/32/64) is what lets a touch player take more than one at a time -
 * shift-click is not reachable on touch. Right-click and shift-click still take a full stack for
 * Java players. This follows the same rule the rest of the GUI work uses: distinct slots, distinct
 * items, and no click-type trickery.
 */
public final class CatalogueMenu implements InventoryHolder {

    public static final int SIZE = 54;

    /** Tabs occupy the first row: one slot per enum constant. */
    private static final int TAB_COUNT = CatalogueCategory.values().length;
    private static final int CONTENT_FIRST = TAB_COUNT;
    private static final int CONTENT_SLOTS = 36;

    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_AMOUNT = 47;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_CLOSE = 53;
    private static final int NAV_FIRST = 45;

    /** What a plain click takes, cycled by the amount button. */
    private static final List<Integer> TAKE_AMOUNTS = List.of(1, 8, 16, 32, 64);

    private static final String MESSAGE_TITLE = "catalogue-title";
    private static final String MESSAGE_TAB = "catalogue-tab";
    private static final String MESSAGE_TAB_SELECTED = "catalogue-tab-selected";
    private static final String MESSAGE_EMPTY = "catalogue-empty";

    private final CatalogueIndex index;
    private final MessageRenderer renderer;
    private final Inventory inventory;

    private CatalogueCategory category = CatalogueCategory.ALL;
    private CataloguePage page = CataloguePage.of(0, CONTENT_SLOTS, 0);
    private int amountIndex;

    public CatalogueMenu(CatalogueIndex index, MessageRenderer renderer) {
        this.index = Objects.requireNonNull(index, "index");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.inventory = Bukkit.createInventory(this, SIZE, renderer.render(MESSAGE_TITLE));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Draws the current page and shows the menu. Must be called on the tick thread. */
    public void open(Player player) {
        Objects.requireNonNull(player, "player");
        render();
        player.openInventory(inventory);
        renderer.send(player, "catalogue-hint", Map.of("amount", Integer.toString(selectedAmount())), true);
    }

    /**
     * Switches tabs when a tab-row slot was clicked.
     *
     * @return true when the slot belongs to the tab row, whether or not the tab changed
     */
    public boolean selectTab(int slot) {
        if (slot < 0 || slot >= TAB_COUNT) {
            return false;
        }
        CatalogueCategory clicked = CatalogueCategory.values()[slot];
        if (clicked != category) {
            category = clicked;
            // A new tab always starts at its first page: keeping the old number would open a
            // shorter tab halfway down for no reason, or on a clamped page that looks arbitrary.
            page = CataloguePage.of(index.size(category), CONTENT_SLOTS, 0);
            render();
        }
        return true;
    }

    /**
     * Handles the navigation row.
     *
     * @return true for every slot in that row, so an inert cell is still consumed by the menu
     *         rather than falling through to the "take an item" path
     */
    public boolean navigate(int slot, Player player) {
        if (slot < NAV_FIRST) {
            return false;
        }

        int entries = index.size(category);
        switch (slot) {
            case SLOT_PREVIOUS -> {
                page = page.shift(entries, CONTENT_SLOTS, -1);
                render();
            }
            case SLOT_NEXT -> {
                page = page.shift(entries, CONTENT_SLOTS, 1);
                render();
            }
            case SLOT_AMOUNT -> {
                amountIndex = (amountIndex + 1) % TAKE_AMOUNTS.size();
                render();
            }
            case SLOT_CLOSE -> player.closeInventory();
            // The page cell only reports; the remaining cells are decoration.
            case SLOT_PAGE -> {
            }
            default -> {
            }
        }
        return true;
    }

    /**
     * The item a content slot currently shows.
     *
     * <p>The bounds check against the page size is what makes the empty-tab placeholder and every
     * slot past the end of the page inert without a second lookup table.
     */
    public Optional<Material> content(int slot) {
        int offset = slot - CONTENT_FIRST;
        if (offset < 0 || offset >= page.size()) {
            return Optional.empty();
        }
        return Optional.of(index.of(category).get(page.fromInclusive() + offset));
    }

    /** How many items a click takes: a plain click takes the selected amount, shift a full stack. */
    public int amountFor(ClickType click, Material material) {
        int fullStack = Math.max(1, material.getMaxStackSize());
        return switch (click) {
            case LEFT -> Math.min(selectedAmount(), fullStack);
            case RIGHT, SHIFT_LEFT, SHIFT_RIGHT -> fullStack;
            // Middle-click clone, number keys, Q-drop, offhand swap, double-click collect and the
            // creative-only path all do nothing here: none of them mean "give me this item".
            default -> 0;
        };
    }

    public int selectedAmount() {
        return TAKE_AMOUNTS.get(amountIndex);
    }

    public CatalogueCategory category() {
        return category;
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        inventory.clear();

        CatalogueCategory[] categories = CatalogueCategory.values();
        for (int slot = 0; slot < categories.length; slot++) {
            inventory.setItem(slot, tabItem(categories[slot], categories[slot] == category));
        }

        List<Material> entries = index.of(category);
        page = CataloguePage.of(entries.size(), CONTENT_SLOTS, page.index());
        for (int offset = 0; offset < page.size(); offset++) {
            inventory.setItem(CONTENT_FIRST + offset,
                    new ItemStack(entries.get(page.fromInclusive() + offset)));
        }
        if (page.isEmpty()) {
            // A real item in slot one, but content() refuses it because the page reports no
            // entries, so an empty tab cannot be used to take the placeholder.
            inventory.setItem(CONTENT_FIRST, named(icon("minecraft:barrier"), MESSAGE_EMPTY));
        }

        inventory.setItem(SLOT_PREVIOUS, page.hasPrevious() ? named(icon("minecraft:arrow"), "catalogue-previous") : null);
        inventory.setItem(SLOT_NEXT, page.hasNext() ? named(icon("minecraft:arrow"), "catalogue-next") : null);
        inventory.setItem(SLOT_PAGE, named(icon("minecraft:book"), "catalogue-page", Map.of(
                "page", Integer.toString(page.displayIndex()),
                "pages", Integer.toString(page.pageCount()),
                "entries", Integer.toString(entries.size()))));
        inventory.setItem(SLOT_AMOUNT, named(icon("minecraft:gold_ingot"), "catalogue-amount",
                Map.of("amount", Integer.toString(selectedAmount()))));
        inventory.setItem(SLOT_CLOSE, named(icon("minecraft:barrier"), "catalogue-close"));
    }

    private ItemStack tabItem(CatalogueCategory tab, boolean selected) {
        return named(iconFor(tab), selected ? MESSAGE_TAB_SELECTED : MESSAGE_TAB,
                Map.of("category", tab.displayName()));
    }

    private ItemStack named(Material material, String messageKey) {
        return named(material, messageKey, Map.of());
    }

    private ItemStack named(Material material, String messageKey, Map<String, String> placeholders) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Labels come from messages.yml like every other string in the plugin, so wording is
            // never trapped in bytecode.
            meta.displayName(renderer.render(messageKey, placeholders));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Resolves a button icon by name. A name that this server version does not have degrades to
     * stone rather than failing the render, so a rename upstream costs a button its icon, not the GUI.
     */
    private static Material icon(String key) {
        Material material = Material.matchMaterial(key);
        return material == null ? Material.STONE : material;
    }

    private static Material iconFor(CatalogueCategory tab) {
        return icon(switch (tab) {
            case ALL -> "minecraft:nether_star";
            case BLOCKS -> "minecraft:bricks";
            case REDSTONE -> "minecraft:redstone";
            case TOOLS -> "minecraft:iron_pickaxe";
            case COMBAT -> "minecraft:iron_sword";
            case FOOD -> "minecraft:bread";
            case INGREDIENTS -> "minecraft:brewing_stand";
            case SPAWN -> "minecraft:chicken_spawn_egg";
            case ADMIN -> "minecraft:barrier";
        });
    }
}
