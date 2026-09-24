package net.abled.medieval.paper.catalogue;

import net.abled.medieval.core.catalogue.CatalogueCategory;
import net.abled.medieval.core.catalogue.CatalogueQuery;
import org.bukkit.Material;
import org.bukkit.inventory.CreativeCategory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The items the catalogue offers, sorted into tabs.
 *
 * <p>Built once, on first use, by walking the server's own material registry rather than a
 * hardcoded item list. That is what makes the catalogue correct for whatever version the server
 * runs and what guarantees every material the server knows about is reachable - including the ones
 * a player cannot normally obtain, such as barriers, command blocks and the debug stick, which is
 * the point of an owner tool.
 *
 * <h2>Classification</h2>
 * Tabs come from {@link Material#getCreativeCategory()}, the same data the client builds its own
 * inventory tabs from, with two overrides:
 *
 * <ul>
 *   <li>Spawn eggs get their own tab, because the creative category they report is not a useful
 *       bucket for browsing.</li>
 *   <li>The admin tab is an explicit list. The client keeps command blocks, barriers and debug
 *       sticks in an "operator utilities" group that the server API does not expose, so it cannot
 *       be asked for and has to be named.</li>
 *   <li>Searches run over Everything and ignore the tabs ({@link #search(String)}), because a query
 *       that came back empty only because the wrong tab was open would look broken.</li>
 * </ul>
 *
 * <p>A material the server reports no category for is still listed under Everything rather than
 * being dropped, so a client or server update that adds an unclassified item degrades to "only in
 * Everything" instead of silently disappearing. Every Admin item also stays in Everything.
 *
 * <p>Thread ownership: the index is built lazily and is only ever touched from the tick thread -
 * the command opens a menu and the click handler responds to it - so the guard below is for
 * safety and clarity rather than a claim of concurrency.
 */
public final class CatalogueIndex {

    /**
     * Items an owner opens the catalogue for: unobtainable, only obtainable in creative, or plain
     * awkward to get legitimately. Resolved by name at build time, so a name that a future version
     * removes is skipped instead of breaking the tab.
     */
    private static final List<String> ADMIN_ITEMS = List.of(
            "minecraft:barrier",
            "minecraft:light",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:command_block_minecart",
            "minecraft:structure_block",
            "minecraft:structure_void",
            "minecraft:jigsaw",
            "minecraft:debug_stick",
            "minecraft:spawner",
            "minecraft:trial_spawner",
            "minecraft:vault",
            "minecraft:dragon_egg",
            "minecraft:end_portal_frame",
            "minecraft:bedrock",
            "minecraft:reinforced_deepslate",
            "minecraft:budding_amethyst",
            "minecraft:petrified_oak_slab",
            "minecraft:knowledge_book");

    private static final String SPAWN_EGG_SUFFIX = "_SPAWN_EGG";

    private final Map<CatalogueCategory, List<Material>> tabs = new EnumMap<>(CatalogueCategory.class);
    private boolean built;

    /** The items in one tab, in registry order. Never null; an unused tab is simply empty. */
    public List<Material> of(CatalogueCategory category) {
        ensureBuilt();
        return tabs.getOrDefault(category, List.of());
    }

    public int size(CatalogueCategory category) {
        return of(category).size();
    }

    /**
     * Items matching a search query, in registry order.
     *
     * <p>Searched over Everything rather than the current tab, so a query cannot come back empty
     * just because the player was looking at the wrong tab. Both the item id and the enum name are
     * offered to the matcher, which normalises {@code _}, {@code -} and case, so {@code diamond
     * sword}, {@code diamond_sword} and {@code DIAMOND_SWORD} all find the same item.
     *
     * <p>Thread ownership: call from the tick thread, like every other accessor here.
     */
    public List<Material> search(String query) {
        return of(CatalogueCategory.ALL).stream()
                .filter(material -> CatalogueQuery.matches(query, material.getKey().getKey(), material.name()))
                .toList();
    }

    /** How many items the catalogue knows about in total, for the startup log. */
    public int total() {
        return size(CatalogueCategory.ALL);
    }

    private void ensureBuilt() {
        if (built) {
            return;
        }
        synchronized (this) {
            if (built) {
                return;
            }
            build();
            built = true;
        }
    }

    private void build() {
        Set<Material> adminItems = new LinkedHashSet<>();
        for (String key : ADMIN_ITEMS) {
            Material material = Material.matchMaterial(key);
            if (material != null && isOfferable(material)) {
                adminItems.add(material);
            }
        }

        for (Material material : Material.values()) {
            if (!isOfferable(material)) {
                continue;
            }

            add(CatalogueCategory.ALL, material);

            if (adminItems.contains(material)) {
                add(CatalogueCategory.ADMIN, material);
                continue;
            }
            if (material.name().endsWith(SPAWN_EGG_SUFFIX)) {
                add(CatalogueCategory.SPAWN, material);
                continue;
            }

            CatalogueCategory tab = tabFor(categoryOf(material));
            if (tab != null) {
                add(tab, material);
            }
        }
    }

    /**
     * True for materials that exist as an item and can therefore be put in an inventory.
     *
     * <p>Filtering on {@code isItem()} excludes the technical constants that have no item form -
     * air, the moving piston, portal blocks, wall variants and the like - which are the values that
     * would otherwise show up as unusable holes in the listing.
     */
    private static boolean isOfferable(Material material) {
        return material.isItem() && !material.isAir();
    }

    /**
     * The item's creative category, or null when the server reports none.
     *
     * <p>{@code Material#getCreativeCategory()} is deprecated for removal in the API this build
     * targets - the client is moving away from a fixed set of creative tabs, and Paper's
     * replacement {@code ItemType} accessor is deprecated the same way, so there is currently no
     * supported way to ask. It is used deliberately and confined to this one method.
     *
     * <p>If it is removed, this method is the only place that needs changing, and the catalogue
     * degrades rather than breaking: an item with no category is still listed under Everything
     * (see {@link #build}), so the GUI stays complete and usable - it is only the sorting that is
     * lost.
     */
    @SuppressWarnings("removal")
    private static CreativeCategory categoryOf(Material material) {
        return material.getCreativeCategory();
    }

    /** Maps a client creative category onto one of our tabs; null means "Everything only". */
    private static CatalogueCategory tabFor(CreativeCategory category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case BUILDING_BLOCKS, DECORATIONS -> CatalogueCategory.BLOCKS;
            case REDSTONE -> CatalogueCategory.REDSTONE;
            case TOOLS, TRANSPORTATION, MISC -> CatalogueCategory.TOOLS;
            case COMBAT -> CatalogueCategory.COMBAT;
            case FOOD -> CatalogueCategory.FOOD;
            case BREWING -> CatalogueCategory.INGREDIENTS;
        };
    }

    private void add(CatalogueCategory category, Material material) {
        tabs.computeIfAbsent(category, key -> new ArrayList<>()).add(material);
    }
}
