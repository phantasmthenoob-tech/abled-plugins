package net.abled.medieval.core.search;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a closest-block search must obey, tested against an exhaustive scan of the same world.
 *
 * <p>The equivalence tests are the important ones: they build a world by hand, run the search over it
 * exactly as the server driver does, and compare the answer with a brute-force sweep of every block.
 * An early-exit rule that is even slightly too eager shows up as a different block, rather than as a
 * plausible-looking answer nobody checks.
 */
class BlockSearchSessionTest {

    private static final int MIN_HEIGHT = -64;
    private static final int MAX_HEIGHT = 320;
    private static final int RADIUS = 40;
    private static final SearchOrigin ORIGIN = new SearchOrigin("world", 8.5, 64.0, 8.5);

    @Test
    void matchesAnExhaustiveScanWhenTheAnswerIsInTheSameChunk() {
        FakeWorld world = worldWith(
                chunk(0, 0).withMatch(14, 64, 2),
                chunk(1, 0).withMatch(20, 64, 30));

        assertEquals(new BlockPosition(14, 64, 2), search(world));
    }

    @Test
    void doesNotStopAtThePlayersOwnFirstMatch() {
        // The player's chunk holds a match 8.1 blocks away; the chunk next door holds one 8.0 blocks
        // away. The next ring starts only 7.5 blocks out - the middle of a chunk is far less than a
        // chunk size from its edge - so stopping at the first match found would return the wrong
        // block. This is that case.
        FakeWorld world = worldWith(
                chunk(0, 0).withMatch(0, 64, 9),
                chunk(1, 0).withMatch(16, 64, 8));

        assertEquals(new BlockPosition(16, 64, 8), search(world));
    }

    @Test
    void keepsSearchingPastAnEarlyMatchToReachACloserOneFurtherOut() {
        // A match in ring 1 at 32 blocks and a better one in ring 2 at 24: the walk only reaches the
        // later ring because the ring-1 match is farther than ring 2's closest possible chunk.
        FakeWorld world = worldWith(
                chunk(1, 1).withMatch(31, 64, 31),
                chunk(2, 0).withMatch(32, 64, 8));

        assertEquals(new BlockPosition(32, 64, 8), search(world));
    }

    @Test
    void sweepsTheWholeHeightForABlockDeepUnderground() {
        // Nothing else in the radius matches, so the answer is 103 blocks straight down - which the
        // walk can only reach by reading every section of the chunk it is standing in.
        FakeWorld world = worldWith(chunk(0, 0).withMatch(8, -40, 8));

        assertEquals(new BlockPosition(8, -40, 8), search(world));
    }

    @Test
    void skipsTerrainThatDoesNotExistYet() {
        // Only one chunk in the whole radius has been generated, and it holds nothing. The rest is
        // skipped rather than reported as empty ground, and the search still ends cleanly.
        FakeWorld world = worldWith(chunk(0, 0));

        BlockSearchSession session = new BlockSearchSession(ORIGIN, RADIUS);
        drive(session, world);

        assertEquals(BlockSearchSession.Outcome.NOT_FOUND, session.outcome());
        assertEquals(1, session.chunksRead());
        assertEquals(48, session.chunksSkipped());
        assertTrue(session.found().isEmpty());
    }

    @Test
    void reportsNothingFoundWhenTheBlockIsAbsent() {
        FakeWorld world = new FakeWorld(MIN_HEIGHT, MAX_HEIGHT);
        for (int chunkX = -3; chunkX <= 3; chunkX++) {
            for (int chunkZ = -3; chunkZ <= 3; chunkZ++) {
                world.with(world.generate(chunkX, chunkZ));
            }
        }

        BlockSearchSession session = new BlockSearchSession(ORIGIN, RADIUS);
        drive(session, world);

        assertEquals(BlockSearchSession.Outcome.NOT_FOUND, session.outcome());
        assertEquals(49, session.chunksRead(), "every chunk within the radius was read");
        assertEquals(0, session.chunksSkipped());
    }

    @Test
    void anEmptyWorldIsNotAnError() {
        BlockSearchSession session = new BlockSearchSession(ORIGIN, RADIUS);
        drive(session, new FakeWorld(MIN_HEIGHT, MAX_HEIGHT));

        assertEquals(BlockSearchSession.Outcome.NOT_FOUND, session.outcome());
        assertEquals(49, session.chunksSkipped());
        assertEquals(0, session.chunksRead());
    }

    @Test
    void closestIsOnlyCertainOnceNothingCloserCanBeLeft() {
        BlockSearchSession session = new BlockSearchSession(ORIGIN, RADIUS);
        assertFalse(session.closestIsCertain(), "nothing found yet, so nothing is certain");

        // The driver reads the chunk the player is standing in first.
        ChunkCandidate standing = session.nextCandidate().orElseThrow();
        assertEquals(0, standing.chunkX());

        // A match in the far corner of that chunk is not yet certain: the chunks next door start
        // 7.5 blocks out and could still hold something closer. The chunk containing the player has a
        // lower bound of zero - a block anywhere around them could be in it - so this is conservative
        // by design rather than an oversight.
        session.record(Optional.of(new BlockPosition(15, 64, 15)));
        assertFalse(session.closestIsCertain());

        // A match a block away is certain, though: now that the chunk underfoot has been read, the
        // closest possible block anywhere else is already 7.5 blocks from the player.
        BlockSearchSession close = new BlockSearchSession(ORIGIN, RADIUS);
        close.nextCandidate();
        close.record(Optional.of(new BlockPosition(9, 64, 8)));
        assertTrue(close.closestIsCertain());
    }

    @Test
    void theTimeLimitKeepsWhatWasFoundButNotTheClosestGuarantee() {
        BlockSearchSession session = new BlockSearchSession(ORIGIN, RADIUS);
        session.nextCandidate();
        session.record(Optional.of(new BlockPosition(2, 64, 2)));

        session.timeOut();

        assertEquals(BlockSearchSession.Outcome.TIMED_OUT, session.outcome());
        assertEquals(new BlockPosition(2, 64, 2), session.found().orElseThrow());
        assertEquals(BlockSearchSession.Outcome.TIMED_OUT, session.finish(), "finishing changes nothing");
    }

    @Test
    void resultsThatArriveAfterTheEndAreIgnored() {
        BlockSearchSession session = new BlockSearchSession(ORIGIN, RADIUS);
        session.cancel();

        session.record(Optional.of(new BlockPosition(8, 64, 8)));
        session.recordSkipped();

        assertEquals(BlockSearchSession.Outcome.CANCELLED, session.outcome());
        assertTrue(session.found().isEmpty(), "a late result must not overwrite a reported answer");
        assertEquals(0, session.chunksRead());
        assertEquals(0, session.chunksSkipped());
    }

    @Test
    void countsWhatItReadAndHowFarItReached() {
        BlockSearchSession session = new BlockSearchSession(ORIGIN, RADIUS);
        drive(session, worldWith(chunk(0, 0)));

        assertEquals(1, session.chunksRead());
        assertEquals(48, session.chunksSkipped());
        assertEquals(49, session.candidatesTaken());
        assertEquals(39.5, session.radiusReached(), 1e-9, "the walk reached the last ring inside the radius");
    }

    @Test
    void radiusReachedNeverExceedsTheLimit() {
        BlockSearchSession session = new BlockSearchSession(ORIGIN, 20);
        drive(session, new FakeWorld(MIN_HEIGHT, MAX_HEIGHT));

        assertEquals(7.5, session.radiusReached(), 1e-9, "rings 0 and 1 only");
        assertTrue(session.radiusReached() <= session.radiusLimitBlocks());
    }

    // ------------------------------------------------------------------ harness

    /** Runs the search the way the server driver does, one chunk at a time. */
    private static void drive(BlockSearchSession session, FakeWorld world) {
        while (session.isSearching()) {
            if (session.closestIsCertain()) {
                session.finish();
                return;
            }
            Optional<ChunkCandidate> candidate = session.nextCandidate();
            if (candidate.isEmpty()) {
                session.finish();
                return;
            }
            ChunkCandidate chunk = candidate.get();
            Optional<FakeChunk> blocks = world.at(chunk.chunkX(), chunk.chunkZ());
            if (blocks.isEmpty()) {
                session.recordSkipped();
                continue;
            }
            session.record(ChunkBlockScanner.nearest(blocks.get(), session.origin()));
        }
    }

    private static BlockPosition search(FakeWorld world) {
        BlockSearchSession session = new BlockSearchSession(ORIGIN, RADIUS);
        drive(session, world);

        assertEquals(bruteForce(world, ORIGIN), session.found(),
                "the search must return exactly what an exhaustive scan returns");
        return session.found().orElseThrow();
    }

    /** Every matching block in every chunk the radius covers, nearest first. */
    private static Optional<BlockPosition> bruteForce(FakeWorld world, SearchOrigin origin) {
        BlockPosition best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int span = RADIUS / ChunkCandidate.CHUNK_SIZE + 2;

        for (int chunkX = origin.chunkX() - span; chunkX <= origin.chunkX() + span; chunkX++) {
            for (int chunkZ = origin.chunkZ() - span; chunkZ <= origin.chunkZ() + span; chunkZ++) {
                if (origin.horizontalDistanceToChunk(chunkX, chunkZ) > RADIUS) {
                    continue;
                }
                Optional<FakeChunk> chunk = world.at(chunkX, chunkZ);
                if (chunk.isEmpty()) {
                    continue;
                }
                for (BlockPosition match : chunk.get().matches()) {
                    double distance = origin.distanceSquaredTo(match.x(), match.y(), match.z());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = match;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static FakeChunk chunk(int chunkX, int chunkZ) {
        return new FakeChunk(chunkX, chunkZ, MIN_HEIGHT, MAX_HEIGHT);
    }

    private static FakeWorld worldWith(FakeChunk... chunks) {
        FakeWorld world = new FakeWorld(MIN_HEIGHT, MAX_HEIGHT);
        for (FakeChunk chunk : chunks) {
            world.with(chunk);
        }
        return world;
    }
}
