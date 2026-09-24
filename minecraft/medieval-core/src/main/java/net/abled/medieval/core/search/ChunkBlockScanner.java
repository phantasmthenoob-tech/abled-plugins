package net.abled.medieval.core.search;

import java.util.Objects;
import java.util.Optional;

/**
 * Finds the matching block in one chunk that is closest to the search origin.
 *
 * <p>Deliberately a pure function of its arguments: the platform copies a chunk into a thread-safe
 * form on the tick thread, then calls this from a background thread. That is what keeps a search for
 * a block thousands of blocks away from spending the server's tick budget on block reads, and it is
 * also why the whole of this class is unit-tested against a fake chunk rather than a live world.
 *
 * <p>The sweep is the full height of the chunk, and that is the point - "closest, no matter how far"
 * includes straight down. Empty sections are skipped whole, which is what makes a full-height sweep
 * cheap enough to run 36,000 times in a row.
 */
public final class ChunkBlockScanner {

    private ChunkBlockScanner() {
    }

    /**
     * The matching block in this chunk closest to the origin.
     *
     * @return empty when the chunk holds no matching block
     */
    public static Optional<BlockPosition> nearest(ChunkBlocks chunk, SearchOrigin origin) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(origin, "origin");

        int minHeight = chunk.minHeight();
        int maxHeight = chunk.maxHeight();
        if (maxHeight <= minHeight) {
            return Optional.empty();
        }

        BlockPosition best = null;
        double bestDistanceSquared = Double.POSITIVE_INFINITY;

        int firstSection = minHeight >> 4;
        int lastSection = (maxHeight - 1) >> 4;
        for (int sectionY = firstSection; sectionY <= lastSection; sectionY++) {
            if (chunk.isSectionEmpty(sectionY)) {
                continue;
            }

            int fromY = Math.max(minHeight, sectionY << 4);
            int toY = Math.min(maxHeight, (sectionY << 4) + 16);
            for (int y = fromY; y < toY; y++) {
                for (int localX = 0; localX < 16; localX++) {
                    int blockX = chunk.minBlockX() + localX;
                    for (int localZ = 0; localZ < 16; localZ++) {
                        if (!chunk.matches(localX, y, localZ)) {
                            continue;
                        }

                        // The distance is computed only for blocks that match. Almost every block
                        // read is a rejection, and a rejection only needs the material comparison.
                        int blockZ = chunk.minBlockZ() + localZ;
                        double distanceSquared = origin.distanceSquaredTo(blockX, y, blockZ);
                        if (distanceSquared < bestDistanceSquared) {
                            bestDistanceSquared = distanceSquared;
                            best = new BlockPosition(blockX, y, blockZ);
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
