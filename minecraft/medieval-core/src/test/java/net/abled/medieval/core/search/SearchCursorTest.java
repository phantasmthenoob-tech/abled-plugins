package net.abled.medieval.core.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchCursorTest {

    /** Centred in its chunk, so every ring's closest candidate is one chunk farther than the last. */
    private static final SearchOrigin CENTRED = new SearchOrigin("world", 8.5, 64.0, 8.5);

    /** 40 blocks covers rings 0 to 3: ring 3 starts at 39.5 and ring 4 at 55.5. */
    private static final int RADIUS = 40;

    @Test
    void handsOutTheChunkThePlayerIsStandingInFirst() {
        ChunkCandidate first = new SearchCursor(CENTRED, RADIUS).next().orElseThrow();

        assertEquals(0, first.chunkX());
        assertEquals(0, first.chunkZ());
        assertEquals(0.0, first.lowerBound());
    }

    @Test
    void theNextRingStartsWhereItActuallyStarts() {
        SearchCursor cursor = new SearchCursor(CENTRED, RADIUS);
        cursor.next();

        // The player stands in the middle of chunk 0, so the closest chunk of ring 1 is 7.5 blocks
        // away - not the 16 a "one chunk farther than the last ring" rule would claim. The origin
        // chunk's own bound is 0 because the player is inside it, which is what breaks that rule.
        assertEquals(7.5, cursor.remainingLowerBound(), 1e-9);
    }

    @Test
    void remainingLowerBoundNeverOverestimatesWhatIsLeftToInspect() {
        List<ChunkCandidate> all = drain(new SearchCursor(CENTRED, RADIUS));
        assertTrue(all.size() > 40, "expected several rings, got " + all.size() + " chunks");

        SearchCursor cursor = new SearchCursor(CENTRED, RADIUS);
        for (int dispatched = 0; dispatched < all.size(); dispatched++) {
            double floor = cursor.remainingLowerBound();
            double truth = all.subList(dispatched, all.size()).stream()
                    .mapToDouble(ChunkCandidate::lowerBound)
                    .min()
                    .orElseThrow();

            assertTrue(floor <= truth + 1e-9,
                    "after " + dispatched + " chunk(s) the floor " + floor
                            + " claims more than the closest remaining chunk (" + truth + ")");
            assertEquals(all.get(dispatched), cursor.next().orElseThrow());
        }

        assertTrue(cursor.next().isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, cursor.remainingLowerBound());
    }

    @Test
    void walksRingsOutwardsAndWithinARingNearestFirst() {
        List<ChunkCandidate> all = drain(new SearchCursor(CENTRED, RADIUS));

        int previousRing = -1;
        double previousBound = -1.0;
        for (ChunkCandidate candidate : all) {
            int ring = Math.max(Math.abs(candidate.chunkX()), Math.abs(candidate.chunkZ()));
            assertTrue(ring >= previousRing, "rings must not go back inwards");
            if (ring == previousRing) {
                assertTrue(candidate.lowerBound() >= previousBound,
                        "within a ring, chunks must come nearest first");
            }
            previousRing = ring;
            previousBound = candidate.lowerBound();
        }
    }

    @Test
    void coversEveryChunkOfEveryRingExactlyOnce() {
        List<ChunkCandidate> all = drain(new SearchCursor(CENTRED, RADIUS));
        Set<ChunkCandidate> distinct = new HashSet<>(all);

        assertEquals(all.size(), distinct.size(), "a chunk must not be handed out twice");
        // Rings 0 to 3: one chunk, then 8, 16 and 24.
        assertEquals(1 + 8 * (1 + 2 + 3), all.size());
    }

    @Test
    void stopsAtTheRadiusLimit() {
        SearchCursor cursor = new SearchCursor(CENTRED, 20);
        assertFalse(cursor.isExhausted(), "ring 0 is always inside the radius");

        List<ChunkCandidate> all = drain(cursor);

        assertEquals(1 + 8, all.size(), "rings 0 and 1 only");
        assertTrue(all.stream().allMatch(candidate -> candidate.lowerBound() <= 20.0));
        assertTrue(cursor.isExhausted());
    }

    @Test
    void worksWithNegativeChunkCoordinates() {
        SearchOrigin origin = new SearchOrigin("world", -20.5, 64.0, -30.5);
        SearchCursor cursor = new SearchCursor(origin, RADIUS);

        ChunkCandidate first = cursor.next().orElseThrow();
        assertEquals(-2, first.chunkX());
        assertEquals(-2, first.chunkZ());
        assertEquals(0.0, first.lowerBound());

        for (ChunkCandidate candidate : drain(cursor)) {
            assertEquals(origin.horizontalDistanceToChunk(candidate.chunkX(), candidate.chunkZ()),
                    candidate.lowerBound(), 1e-9);
        }
    }

    @Test
    void radiusReachedGrowsAsTheWalkAdvances() {
        SearchCursor cursor = new SearchCursor(CENTRED, RADIUS);
        assertEquals(0.0, cursor.radiusReached(), 1e-9);

        cursor.next();  // ring 0
        cursor.next();  // first chunk of ring 1
        assertEquals(7.5, cursor.radiusReached(), 1e-9);

        drain(cursor);
        assertTrue(cursor.isExhausted());
    }

    @Test
    void rejectsANegativeRadiusLimit() {
        assertThrows(IllegalArgumentException.class, () -> new SearchCursor(CENTRED, -1));
    }

    private static List<ChunkCandidate> drain(SearchCursor cursor) {
        List<ChunkCandidate> chunks = new ArrayList<>();
        Optional<ChunkCandidate> next = cursor.next();
        while (next.isPresent()) {
            chunks.add(next.get());
            next = cursor.next();
        }
        return chunks;
    }
}
