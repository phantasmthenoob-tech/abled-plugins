package net.abled.medieval.core.territory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPositionTest {

    @Test
    void convertsBlockCoordinatesToChunks() {
        assertEquals(new ChunkPosition("world", 0, 0), ChunkPosition.ofBlock("world", 0, 15));
        assertEquals(new ChunkPosition("world", 1, 1), ChunkPosition.ofBlock("world", 16, 31));
    }

    @Test
    void handlesNegativeCoordinates() {
        assertEquals(new ChunkPosition("world", -1, -1), ChunkPosition.ofBlock("world", -1, -16));
        assertEquals(new ChunkPosition("world", -2, -2), ChunkPosition.ofBlock("world", -17, -32));
    }

    @Test
    void reportsMinimumBlockCoordinates() {
        ChunkPosition position = new ChunkPosition("world", -1, 3);

        assertEquals(-16, position.minBlockX());
        assertEquals(48, position.minBlockZ());
    }

    @Test
    void containsBlockOnlyForItsOwnChunk() {
        ChunkPosition position = ChunkPosition.ofBlock("world", -5, 40);

        assertTrue(position.containsBlock(-5, 40));
        assertTrue(position.containsBlock(-1, 47));
        assertFalse(position.containsBlock(0, 40));
    }

    @Test
    void storageKeyRoundTrips() {
        ChunkPosition original = new ChunkPosition("world_nether", -12, 8);

        assertEquals("world_nether;-12;8", original.storageKey());
        assertEquals(original, ChunkPosition.parse(original.storageKey()));
    }

    @Test
    void chunksInDifferentWorldsAreDistinct() {
        assertNotEquals(new ChunkPosition("world", 1, 1), new ChunkPosition("world_nether", 1, 1));
    }

    @Test
    void rejectsMalformedKeys() {
        assertThrows(IllegalArgumentException.class, () -> ChunkPosition.parse("world;1"));
        assertThrows(IllegalArgumentException.class, () -> ChunkPosition.parse("world;x;1"));
        assertThrows(IllegalArgumentException.class, () -> ChunkPosition.parse(""));
    }

    @Test
    void rejectsInvalidWorldNames() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkPosition(" ", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkPosition("world;nether", 0, 0));
    }
}
