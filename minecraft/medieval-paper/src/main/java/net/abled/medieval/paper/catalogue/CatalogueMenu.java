package net.abled.medieval.paper.catalogue;

import net.abled.medieval.core.catalogue.CatalogueCategory;
import net.abled.medieval.core.catalogue.CataloguePage;
import net.abled.medieval.core.catalogue.CatalogueQuery;
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
 * One player's view of the owner catalogue: a chest inventory of items to take, with a search box.
 *
 * <h2>Layout</h2>
 * <pre>
 *   row 1 (slots  0- 8)  category tabs - one per {@link CatalogueCategory}, in declaration order
 *   rows 2-5 (slots 9-44) up to 36 items, the current page of the current tab or search
 *   row 6 (slots 45-53)  previous | search | take amount | custom amount | enchant | clear search
 *                        | page | next | close
 * </pre>
 *
 * <h2>Two modes</h2>
 * <em>Browsing</em> shows a tab and its pages. <em>Searching</em> shows the matches for a query and
 * ignores the tabs; clicking any tab leaves the search, which is why a search cannot become a dead
 * end. Every cell in the navigation row is always drawn - a previous/next button that is not
 * available is shown dimmed and does nothing, rather than being left blank, so the row reads the
 * same on page one as on page ten.
 *
 * <h2>Why a custom {@link InventoryHolder}</h2>
 * The holder is this object, which is how the click listener recognises its own inventory and
 * reads the page, tab and search that were on screen when a button was pressed. Storing that state
 * on the inventory instead of in a map keyed by UUID means two menus opened by the same player, or a
 * menu left open across a reload, still answer with their own state.
 *
 * <h2>Bedrock</h2>
 * Nothing here depends on telling a left-click from a right-click, because Geyser cannot: on
 * Bedrock every tap arrives as {@code LEFT}. A tap therefore takes the selected amount, and the
 * Take amount button (1/8/16/32/64) is what lets a touch player take more than one at a time -
 * shift-click is not reachable on touch. Right-click and shift-click still take a full stack for
 * Java players. Searching, custom amounts and enchantment levels are typed into chat rather than a
 * sign or anvil, because the sign editor and anvil renaming are exactly the screens Geyser does not
 * carry over. This follows the same rule the rest of the GUI work uses: distinct slots, distinct
 * items, and no click-type trickery.
 */
public final class CatalogueMenu implements InventoryHolder {

    public static final int SIZE = 54;

    /** Tabs occupy the first row: one slot per enum constant. */
    private static final int TAB_COUNT = CatalogueCategory.values().length;
    private static final int CONTENT_FIRST = TAB_COUNT;
    private static final int CONTENT_SLOTS = 36;

    private static final int SLOT_PREVIOUS = 45;
    /** Opens the search prompt; also the button that shows the active query. */
    public static final int SLOT_SEARCH = 46;
    private static final int SLOT_AMOUNT = 47;
    /** Asks for a take amount in chat; also shows the custom amount while one is in force. */
    public static final int SLOT_CUSTOM_AMOUNT = 48;
    /** Arms enchant mode: the next item click opens the enchantment picker instead of taking. */
    public static final int SLOT_ENCHANT = 49;
    /** Leaves the search and returns to the tab that was open before it. */
    public static final int SLOT_CLEAR_SEARCH = 50;
    private static final int SLOT_PAGE = 51;
    private static final int SLOT_NEXT = 52;
    private static final int SLOT_CLOSE = 53;
    private static final int NAV_FIRST = 45;

    /** What a plain click takes, cycled by the amount button. */
    private static final List<Integer> TAKE_AMOUNTS = List.of(1, 8, 16, 32, 64);

    private static final String MESSAGE_TITLE = "catalogue-title";
    private static final String MESSAGE_TAB = "catalogue-tab";
    private static final String MESSAGE_TAB_SELECTED = "catalogue-tab-selected";
    private static final String MESSAGE_EMPTY = "catalogue-empty";
    private static final String MESSAGE_SEARCH_EMPTY = "catalogue-search-nothing";
    /** Hover text. Every visible element explains itself, since a button with only a label is a
     *  guessing game - especially on Bedrock, where the click rules are not the Java ones. */
    private static final String LORE_ITEM = "catalogue-item-lore";
    private static final String LORE_TAB = "catalogue-tab-lore";
    private static final String LORE_EMPTY = "catalogue-empty-lore";
    private static final String LORE_SEARCH_EMPTY = "catalogue-search-nothing-lore";
    private static final String LORE_PREVIOUS = "catalogue-previous-lore";
    private static final String LORE_PREVIOUS_UNAVAILABLE = "catalogue-no-previous-lore";
    private static final String LORE_NEXT = "catalogue-next-lore";
    private static final String LORE_NEXT_UNAVAILABLE = "catalogue-no-next-lore";
    private static final String LORE_PAGE = "catalogue-page-lore";
    private static final String LORE_AMOUNT = "catalogue-amount-lore";
    private static final String LORE_CUSTOM_AMOUNT = "catalogue-custom-amount-lore";
    private static final String LORE_CUSTOM_AMOUNT_ARMED = "catalogue-custom-amount-armed-lore";
    private static final String LORE_CUSTOM_AMOUNT_ACTIVE = "catalogue-custom-amount-active-lore";
    private static final String LORE_ENCHANT = "catalogue-enchant-lore";
    private static final String LORE_ENCHANT_ARMED = "catalogue-enchant-armed-lore";
    private static final String LORE_SEARCH = "catalogue-search-lore";
    private static final String LORE_SEARCH_ACTIVE = "catalogue-search-active-lore";
    private static final String LORE_SEARCH_CLEAR = "catalogue-search-clear-lore";
    private static final String LORE_SEARCH_IDLE = "catalogue-search-idle-lore";
    private static final String LORE_CLOSE = "catalogue-close-lore";

    private final CatalogueIndex index;
    private final MessageRenderer renderer;
    private final Inventory inventory;

    private CatalogueCategory category = CatalogueCategory.ALL;
    private CataloguePage page = CataloguePage.of(0, CONTENT_SLOTS, 0);
    private int amountIndex;

    /** The active search, or null while browsing. Held normalised, as the matcher compares it. */
    private String query;
    private List<Material> results = List.of();

    /**
     * A take amount typed into chat, in force until changed. Unlike the cycled preset, a custom
     * amount is not echoed by the Take amount button - it has its own button showing the number.
     */
    private int customAmount = 0;
    /** True while the next item click should ask for its amount in chat. */
    private boolean customAmountArmed;
    /** True while the next item click should open the enchantment picker instead of taking. */
    private boolean enchantArmed;

    public CatalogueMenu(CatalogueIndex index, MessageRenderer renderer) {
        this.index = Objects.requireNonNull(index, "index");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.inventory = Bukkit.createInventory(this, SIZE, renderer.render(MESSAGE_TITLE));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Draws the current page and shows the menu, with the click hint. Must run on the tick thread. */
    public void open(Player player) {
        open(player, true);
    }

    /**
     * Shows the menu again, optionally without repeating the click hint - reopening after a search
     * keeps the hint, reopening after an empty result does not need to say it twice.
     *
     * <p>Must be called on the tick thread.
     */
    public void open(Player player, boolean announceHint) {
        Objects.requireNonNull(player, "player");
        render();
        player.openInventory(inventory);
        if (announceHint) {
            renderer.send(player, "catalogue-hint",
                    Map.of("amount", Integer.toString(selectedAmount())), true);
        }
    }

    /**
     * Switches tabs when a tab-row slot was clicked.
     *
     * <p>Clicking a tab always leaves a search, including the tab that was already open: that is
     * what makes the tab row the way back out of a search.
     *
     * @return true when the slot belongs to the tab row, whether or not anything changed
     */
    public boolean selectTab(int slot) {
        if (slot < 0 || slot >= TAB_COUNT) {
            return false;
        }

        CatalogueCategory clicked = CatalogueCategory.values()[slot];
        if (clicked == category && query == null) {
            return true;
        }

        category = clicked;
        // A new tab (or leaving a search) always starts at its first page: keeping the old number
        // would open a shorter tab halfway down for no reason, or on a clamped page that looks
        // arbitrary.
        clearSearchState();
        page = CataloguePage.of(entries().size(), CONTENT_SLOTS, 0);
        render();
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

        int entries = entries().size();
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
            // Search and clear-search never arrive here: the click listener handles them, because
            // one closes the menu and the other leaves the search. The page cell only reports.
            default -> {
            }
        }
        return true;
    }

    /**
     * Shows the matches for a query, across every item rather than the current tab.
     *
     * <p>Must be called on the tick thread: it renders.
     *
     * @return true when a search was started; false for a blank query, which is ignored rather
     *         than turning into a search for nothing
     */
    public boolean search(String query) {
        String wanted = CatalogueQuery.normalize(query);
        if (wanted.isEmpty()) {
            return false;
        }

        // The typed text is kept for display; only the normalised form decides what matches, so a
        // query echoed back to the player reads the way they wrote it.
        this.query = query.trim();
        this.results = index.search(wanted);
        this.page = CataloguePage.of(results.size(), CONTENT_SLOTS, 0);
        render();
        return true;
    }

    /**
     * Leaves the search and goes back to browsing the current tab.
     *
     * @return true when there was a search to leave
     */
    public boolean clearSearch() {
        if (query == null) {
            return false;
        }

        clearSearchState();
        page = CataloguePage.of(entries().size(), CONTENT_SLOTS, 0);
        render();
        return true;
    }

    /** True while the menu is showing search results rather than a tab. */
    public boolean isSearching() {
        return query != null;
    }

    public String query() {
        return query;
    }

    public int resultCount() {
        return results.size();
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
        return Optional.of(entries().get(page.fromInclusive() + offset));
    }

    /**
     * Remembers a take amount typed into chat, until it is changed or the menu is rebuilt.
     *
     * @return true when the amount was accepted; false for a value the item rules cannot hand out
     */
    public boolean setCustomAmount(int amount) {
        if (amount < 1) {
            return false;
        }
        this.customAmount = amount;
        render();
        return true;
    }

    /** True while a chat-typed amount is in force, overriding the cycled preset. */
    public boolean hasCustomAmount() {
        return customAmount > 0;
    }

    /** The chat-typed amount, or 0 while none is set. */
    public int customAmount() {
        return customAmount;
    }

    /** Drops a chat-typed amount, returning to the cycled preset. */
    public void clearCustomAmount() {
        if (customAmount == 0) {
            return;
        }
        customAmount = 0;
        render();
    }

    /** Arms or disarms custom-amount mode: the next item click asks for its amount in chat. */
    public void setCustomAmountArmed(boolean armed) {
        this.customAmountArmed = armed;
        if (armed) {
            enchantArmed = false;
        }
        render();
    }

    /** True while the next item click should ask for a take amount in chat. */
    public boolean isCustomAmountArmed() {
        return customAmountArmed;
    }

    /** Arms or disarms enchant mode, re-rendering the armed button and the entries' lore. */
    public void setEnchantArmed(boolean armed) {
        this.enchantArmed = armed;
        if (armed) {
            customAmountArmed = false;
        }
        render();
    }

    /** True while the next item click should open the enchantment picker. */
    public boolean isEnchantArmed() {
        return enchantArmed;
    }

    /**
     * How many items a click takes.
     *
     * <p>A chat-typed amount overrides the cycled preset for plain clicks (right- and shift-click
     * still take a full stack, because "more than what I typed" is what those have always meant
     * here). The cap stays the item's own stack size: one click hands out one stack.
     */
    public int amountFor(ClickType click, Material material) {
        int fullStack = Math.max(1, material.getMaxStackSize());
        return switch (click) {
            case LEFT -> hasCustomAmount()
                    ? Math.min(customAmount, fullStack)
                    : Math.min(selectedAmount(), fullStack);
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

        List<Material> entries = entries();
        page = CataloguePage.of(entries.size(), CONTENT_SLOTS, page.index());
        for (int offset = 0; offset < page.size(); offset++) {
            inventory.setItem(CONTENT_FIRST + offset, entry(entries.get(page.fromInclusive() + offset)));
        }
        if (page.isEmpty()) {
            // A real item in slot one, but content() refuses it because the page reports no
            // entries, so an empty page cannot be used to take the placeholder.
            inventory.setItem(CONTENT_FIRST, isSearching()
                    ? button(icon("minecraft:barrier"), MESSAGE_SEARCH_EMPTY, Map.of("query", query), LORE_SEARCH_EMPTY)
                    : button(icon("minecraft:barrier"), MESSAGE_EMPTY, Map.of(), LORE_EMPTY));
        }

        Map<String, String> pagePlaceholders = Map.of(
                "page", Integer.toString(page.displayIndex()),
                "pages", Integer.toString(page.pageCount()),
                "entries", Integer.toString(entries.size()));

        // Both page buttons are always drawn: the one that cannot be used is dimmed and inert, so
        // the row does not change shape between the first page and the last.
        inventory.setItem(SLOT_PREVIOUS, page.hasPrevious()
                ? button(icon("minecraft:arrow"), "catalogue-previous", Map.of(), LORE_PREVIOUS)
                : button(icon("minecraft:gray_dye"), "catalogue-no-previous", Map.of(), LORE_PREVIOUS_UNAVAILABLE));
        inventory.setItem(SLOT_NEXT, page.hasNext()
                ? button(icon("minecraft:spectral_arrow"), "catalogue-next", Map.of(), LORE_NEXT)
                : button(icon("minecraft:light_gray_dye"), "catalogue-no-next", Map.of(), LORE_NEXT_UNAVAILABLE));

        inventory.setItem(SLOT_SEARCH, isSearching()
                ? button(icon("minecraft:compass"), "catalogue-search-active",
                        Map.of("query", query, "entries", Integer.toString(results.size())), LORE_SEARCH_ACTIVE)
                : button(icon("minecraft:compass"), "catalogue-search", Map.of(), LORE_SEARCH));
        inventory.setItem(SLOT_CLEAR_SEARCH, isSearching()
                ? button(icon("minecraft:red_dye"), "catalogue-search-clear",
                        Map.of("query", query, "entries", Integer.toString(results.size())), LORE_SEARCH_CLEAR)
                : button(icon("minecraft:structure_void"), "catalogue-search-idle", Map.of(), LORE_SEARCH_IDLE));

        inventory.setItem(SLOT_PAGE, button(icon("minecraft:book"), "catalogue-page", pagePlaceholders, LORE_PAGE));
        inventory.setItem(SLOT_AMOUNT, button(icon("minecraft:gold_ingot"), "catalogue-amount",
                Map.of("amount", Integer.toString(selectedAmount())), LORE_AMOUNT));
        // The custom-amount button is always drawn: while no amount is set it arms the mode that
        // asks in chat, while one is set it shows the number and clicking it clears the override.
        inventory.setItem(SLOT_CUSTOM_AMOUNT, hasCustomAmount()
                ? button(icon("minecraft:sunflower"), "catalogue-custom-amount-active",
                        Map.of("amount", Integer.toString(customAmount)), LORE_CUSTOM_AMOUNT_ACTIVE)
                : button(icon("minecraft:gold_nugget"),
                        customAmountArmed ? "catalogue-custom-amount-armed" : "catalogue-custom-amount",
                        Map.of(), customAmountArmed ? LORE_CUSTOM_AMOUNT_ARMED : LORE_CUSTOM_AMOUNT));
        inventory.setItem(SLOT_ENCHANT, enchantArmed
                ? button(icon("minecraft:enchanted_book"), "catalogue-enchant-armed", Map.of(), LORE_ENCHANT_ARMED)
                : button(icon("minecraft:enchanted_book"), "catalogue-enchant", Map.of(), LORE_ENCHANT));
        inventory.setItem(SLOT_CLOSE, button(icon("minecraft:barrier"), "catalogue-close", Map.of(), LORE_CLOSE));
    }

    /** The list the current page is a window into: a tab's items, or the search results. */
    private List<Material> entries() {
        return query == null ? index.of(category) : results;
    }

    private void clearSearchState() {
        query = null;
        results = List.of();
    }

    private ItemStack tabItem(CatalogueCategory tab, boolean selected) {
        return button(iconFor(tab), selected ? MESSAGE_TAB_SELECTED : MESSAGE_TAB,
                Map.of("category", tab.displayName()), LORE_TAB);
    }

    /**
     * A catalogue entry: the real item, with its real name, plus a hint in its hover text.
     *
     * <p>The hint is presentation only. The listener builds a clean {@code new ItemStack(material)}
     * from the material, so no lore or display name from the menu is ever handed to the player.
     */
    private ItemStack entry(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // In enchant or custom-amount mode the entries' hover text explains what a click now
            // does; the modes are armed per click, so the lore must follow the state.
            meta.lore(renderer.renderLines(
                    enchantArmed ? "catalogue-item-lore-enchant"
                            : customAmountArmed ? "catalogue-item-lore-amount" : LORE_ITEM,
                    Map.of("amount", Integer.toString(selectedAmount()))));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack button(Material material, String labelKey, Map<String, String> placeholders, String loreKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Labels and lore come from messages.yml like every other string in the plugin, so
            // wording is never trapped in bytecode.
            meta.displayName(renderer.render(labelKey, placeholders));
            meta.lore(renderer.renderLines(loreKey, placeholders));
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
