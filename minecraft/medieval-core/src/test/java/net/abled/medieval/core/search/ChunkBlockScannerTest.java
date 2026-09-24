package net.abled.medieval.core.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBlockScannerTest {

    private static final int MIN_HEIGHT = -64;
    private static final int MAX_HEIGHT = 320;
    private static final SearchOrigin ORIGIN = new SearchOrigin("world", 8.5, 64.0, 8.5);

    @Test
    void findsTheClosestMatchInTheChunk() {
        FakeChunk chunk = new FakeChunk(0, 0, MIN_HEIGHT, MAX_HEIGHT)
                .withMatch(1, 64, 1)
                .withMatch(14, 64, 14);

        assertEquals(new BlockPosition(14, 64, 14), nearest(chunk));
    }

    @Test
    void mapsLocalCoordinatesOntoWorldCoordinates() {
        // Chunk (-2, 3) starts at x = -32, z = 48, so this block sits at local (2, 2).
        FakeChunk chunk = new FakeChunk(-2, 3, MIN_HEIGHT, MAX_HEIGHT).withMatch(-30, 70, 50);

        assertEquals(new BlockPosition(-30, 70, 50), nearest(chunk));
    }

    @Test
    void measuresThreeDimensionalDistance() {
        // 23.5 blocks straight down beats 32 blocks across: "closest" is not a horizontal question,
        // which is what makes a search for a block deep underground work at all.
        FakeChunk chunk = new FakeChunk(0, 0, MIN_HEIGHT, MAX_HEIGHT)
                .withMatch(8, 40, 8)
                .withMatch(8, 64, 40);

        assertEquals(new BlockPosition(8, 40, 8), nearest(chunk));
    }

    @Test
    void returnsEmptyWhenTheChunkHoldsNoMatch() {
        assertTrue(ChunkBlockScanner.nearest(new FakeChunk(0, 0, MIN_HEIGHT, MAX_HEIGHT), ORIGIN).isEmpty());
    }

    @Test
    void skipsEmptySectionsWithoutReadingThem() {
        FakeChunk chunk = new FakeChunk(0, 0, 0, 64)
                .withMatch(4, 8, 4)
                .withEmptySections(1, 2, 3);

        assertEquals(new BlockPosition(4, 8, 4), nearest(chunk));
        assertEquals(4096, chunk.matchesCalls(), "only the section holding blocks was read");
        assertEquals(4, chunk.emptySectionChecks(), "all four sections were considered");
    }

    @Test
    void staysInsideTheWorldHeightRange() {
        // A world that ends at y = -16. The match at y = 0 is not in this world and must not be
        // reported, and the scan must not read above the ceiling either.
        FakeChunk chunk = new FakeChunk(0, 0, -64, -16)
                .withMatch(1, -64, 1)
                .withMatch(2, 0, 2);

        assertEquals(new BlockPosition(1, -64, 1), nearest(chunk));
        assertEquals(3 * 4096, chunk.matchesCalls(), "sections -4, -3 and -2 only");
    }

    @Test
    void treatsAChunkWithNoHeightAsEmpty() {
        assertTrue(ChunkBlockScanner.nearest(new FakeChunk(0, 0, 64, 64), ORIGIN).isEmpty());
    }

    private static BlockPosition nearest(FakeChunk chunk) {
        return ChunkBlockScanner.nearest(chunk, ORIGIN).orElseThrow();
    }
}
