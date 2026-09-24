package net.abled.medieval.api.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomItemIdTest {

    @Test
    void parsesNamespacedKeys() {
        CustomItemId id = CustomItemId.of("medieval:longsword");

        assertEquals("medieval:longsword", id.key());
        assertEquals("medieval", id.namespace());
        assertEquals("longsword", id.path());
    }

    @Test
    void buildsIdsFromNamespaceAndPath() {
        assertEquals(CustomItemId.of("medieval:steel_ingot"), CustomItemId.of("medieval", "steel_ingot"));
        assertEquals("medieval:warhammer", CustomItemId.medieval("warhammer").key());
    }

    @Test
    void acceptsPathsWithNestedSegments() {
        assertEquals("medieval:armor/knight_helmet", CustomItemId.medieval("armor/knight_helmet").key());
    }

    @Test
    void rejectsInvalidIds() {
        assertThrows(IllegalArgumentException.class, () -> CustomItemId.of("Longsword"));
        assertThrows(IllegalArgumentException.class, () -> CustomItemId.of("medieval:"));
        assertThrows(IllegalArgumentException.class, () -> CustomItemId.of(":longsword"));
        assertThrows(IllegalArgumentException.class, () -> CustomItemId.of("medieval:Long Sword"));
        assertThrows(IllegalArgumentException.class, () -> CustomItemId.of(""));
        assertThrows(NullPointerException.class, () -> CustomItemId.of((String) null));
    }

    @Test
    void equalityIsBasedOnTheKey() {
        CustomItemId first = CustomItemId.medieval("spear");
        CustomItemId second = CustomItemId.of("medieval:spear");
        CustomItemId other = CustomItemId.medieval("halberd");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
        assertEquals("medieval:spear", first.toString());
    }
}
