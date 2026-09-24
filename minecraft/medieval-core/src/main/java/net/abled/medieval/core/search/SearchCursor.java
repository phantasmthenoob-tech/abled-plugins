package net.abled.medieval.core.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hands out the chunks a closest-block search should inspect, nearest first, until the radius limit
 * is reached.
 *
 * <h2>Why rings</h2>
 * Chunks are walked in Chebyshev rings around the origin chunk - ring 0 is the origin chunk itself,
 * ring r is the square of chunks exactly r chunks away. That ordering is what lets a search that must
 * look "no matter how far" still end early: once a matching block has been found, every chunk still
 * queued is at least {@link #remainingLowerBound()} away, and the moment nothing left can beat the
 * best block, the answer is already known.
 *
 * <h2>What makes the bound sound</h2>
 * {@link #remainingLowerBound()} must never overestimate how close an uninspected chunk could be, or
 * the search would stop early and return a block that is not the closest one. Two things guarantee it,
 * and both are covered by tests:
 *
 * <ul>
 *   <li>candidates within a ring are ordered by their lower bound, so the next candidate is the closest
 *       thing left in that ring;</li>
 *   <li>the following ring's closest candidate is computed exactly, from its four axial chunks, rather
 *       than assumed to be one chunk size farther out. That assumption happens to hold between every
 *       pair of rings <em>except</em> the first: ring 0 is the chunk the player is standing in, which
 *       can be arbitrarily far from its own edge, so ring 1 may start much closer than 16 blocks
 *       away.</li>
 * </ul>
 *
 * <p>The cursor is stateful and single-threaded by design: a search inspects chunks over many ticks,
 * and the cursor is what remembers where it got to. It does no I/O - it only decides what to look at
 * next - so every platform drives it the same way.
 */
public final class SearchCursor {

    private final SearchOrigin origin;
    private final int radiusLimitBlocks;

    private int ring;
    private List<ChunkCandidate> current = List.of();
    private int index;
    private double ringFloor = Double.POSITIVE_INFINITY;
    private double nextRingFloor = Double.POSITIVE_INFINITY;
    private boolean exhausted;

    /**
     * @param origin            where the search starts
     * @param radiusLimitBlocks how far out the search may look, in blocks; a candidate whose whole
     *                          chunk is farther away than this ends the walk
     */
    public SearchCursor(SearchOrigin origin, int radiusLimitBlocks) {
        this.origin = Objects.requireNonNull(origin, "origin");
        if (radiusLimitBlocks < 0) {
            throw new IllegalArgumentException("radius limit must not be negative: " + radiusLimitBlocks);
        }
        this.radiusLimitBlocks = radiusLimitBlocks;
        advanceRing();
    }

    /**
     * The next chunk to inspect, nearest first.
     *
     * @return empty when every chunk within the radius limit has been handed out
     */
    public Optional<ChunkCandidate> next() {
        if (exhausted) {
            return Optional.empty();
        }
        if (index >= current.size()) {
            advanceRing();
            if (exhausted) {
                return Optional.empty();
            }
        }
        return Optional.of(current.get(index++));
    }

    /**
     * The closest any chunk that has not been handed out yet could be, in blocks.
     *
     * <p>Never an overestimate, so a search may finish as soon as its best result is at least this
     * close: nothing left to inspect can beat it. Returns {@link Double#POSITIVE_INFINITY} once the
     * walk is over, which makes "nothing left" satisfy that comparison too.
     */
    public double remainingLowerBound() {
        double nextInRing = index < current.size()
                ? current.get(index).lowerBound()
                : Double.POSITIVE_INFINITY;
        return Math.min(nextInRing, nextRingFloor);
    }

    /**
     * How far out the walk has reached, in blocks: the lower bound of the ring being handed out. Used
     * for progress reporting, so {@code /land status} can say "nothing yet, 480 blocks out" instead of
     * leaving the player guessing.
     */
    public double radiusReached() {
        return ringFloor;
    }

    /** True once every chunk within the radius limit has been handed out. */
    public boolean isExhausted() {
        return exhausted;
    }

    public int radiusLimitBlocks() {
        return radiusLimitBlocks;
    }

    public SearchOrigin origin() {
        return origin;
    }

    private void advanceRing() {
        List<ChunkCandidate> ringChunks = ringChunks(ring);
        if (ringChunks.get(0).lowerBound() > radiusLimitBlocks) {
            // Ring 0 contains the player, so its bound is 0 and this cannot fire there. Beyond it the
            // bounds only grow from ring to ring, so the closest candidate being out of range means
            // every later one is too.
            exhausted = true;
            current = List.of();
            index = 0;
            // ringFloor is deliberately left where it was: nothing is closer than the bound of the last
            // ring still inside the radius, and progress reporting would otherwise report the walk as
            // having reached nothing at all.
            nextRingFloor = Double.POSITIVE_INFINITY;
            return;
        }
        current = ringChunks;
        index = 0;
        ringFloor = ringChunks.get(0).lowerBound();
        nextRingFloor = axialFloor(ring + 1);
        ring++;
    }

    /**
     * The closest a chunk in the given ring can be, in blocks.
     *
     * <p>The four chunks sharing an axis with the origin chunk are the closest of any ring - they are
     * the perpendicular from the player to that ring's inner edge - so this is exact, and needs no ring
     * to be generated first.
     */
    private double axialFloor(int ring) {
        if (ring <= 0) {
            return 0.0;
        }
        int centreX = origin.chunkX();
        int centreZ = origin.chunkZ();
        double east = origin.horizontalDistanceToChunk(centreX + ring, centreZ);
        double west = origin.horizontalDistanceToChunk(centreX - ring, centreZ);
        double south = origin.horizontalDistanceToChunk(centreX, centreZ + ring);
        double north = origin.horizontalDistanceToChunk(centreX, centreZ - ring);
        return Math.min(Math.min(east, west), Math.min(south, north));
    }

    /** The border chunks of one ring, ordered by how close each gets to the player. */
    private List<ChunkCandidate> ringChunks(int ring) {
        int centreX = origin.chunkX();
        int centreZ = origin.chunkZ();
        if (ring == 0) {
            return List.of(candidate(centreX, centreZ));
        }

        List<ChunkCandidate> chunks = new ArrayList<>(8 * ring);
        // The 8r chunks of the ring, generated side by side rather than by scanning the whole bounding
        // square: ring 1000 would otherwise cost four million checks to produce eight thousand chunks.
        for (int offset = -ring; offset <= ring; offset++) {
            chunks.add(candidate(centreX + offset, centreZ - ring));
            chunks.add(candidate(centreX + offset, centreZ + ring));
        }
        for (int offset = -ring + 1; offset <= ring - 1; offset++) {
            chunks.add(candidate(centreX - ring, centreZ + offset));
            chunks.add(candidate(centreX + ring, centreZ + offset));
        }
        // Stable, so chunks that are equally close are inspected in a fixed order and a search is
        // reproducible rather than dependent on the sort's mood.
        chunks.sort(Comparator.comparingDouble(ChunkCandidate::lowerBound));
        return chunks;
    }

    private ChunkCandidate candidate(int chunkX, int chunkZ) {
        return new ChunkCandidate(chunkX, chunkZ, origin.horizontalDistanceToChunk(chunkX, chunkZ));
    }
}
