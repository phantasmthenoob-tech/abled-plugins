package net.abled.medieval.core.catalogue;

import java.util.Objects;
import java.util.Optional;

/**
 * The tabs of the owner catalogue.
 *
 * <p>Exactly nine, so a whole category row fits one inventory line and no tab needs a submenu.
 *
 * <p>These are the plugin's own categories rather than the client's creative tabs. The client has
 * no category the server can ask for that holds command blocks, barriers and debug sticks - those
 * live in an "operator utilities" tab the server API does not expose - so the admin tab is built
 * from an explicit item list in the platform layer. Splitting blocks from redstone from tools is
 * what makes a catalogue of every obtainable item browsable at all.
 *
 * <p>Only identity and label live here. Which item belongs in which tab is decided in the platform
 * layer, where the server's item registry is available; the core must not depend on it.
 */
public enum CatalogueCategory {

    /** Every offered item, unsorted. Always the safety net: nothing is reachable only here. */
    ALL("all", "Everything"),
    BLOCKS("blocks", "Building Blocks"),
    REDSTONE("redstone", "Redstone"),
    TOOLS("tools", "Tools & Utilities"),
    COMBAT("combat", "Combat"),
    FOOD("food", "Food & Drinks"),
    INGREDIENTS("ingredients", "Ingredients"),
    SPAWN("spawn", "Spawn Eggs"),
    /** Unobtainable and administrative items: barriers, command blocks, debug stick, and so on. */
    ADMIN("admin", "Admin & Technical");

    private final String id;
    private final String displayName;

    CatalogueCategory(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Stable id, for logs and configuration - never the display name, which may be reworded. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<CatalogueCategory> byId(String id) {
        Objects.requireNonNull(id, "id");
        for (CatalogueCategory category : values()) {
            if (category.id.equalsIgnoreCase(id.trim())) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
