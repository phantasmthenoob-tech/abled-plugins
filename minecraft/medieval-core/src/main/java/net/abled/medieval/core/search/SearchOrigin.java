package net.abled.medieval.core.search;

import java.util.Objects;

/**
 * Where a block search starts from: the world, the exact position of the player who asked, and - by
 * derivation - the chunk that position falls in.
 *
 * <p>Kept as {@code double} coordinates rather than a block position on purpose. {@code /land search}
 * asks for the closest block <em>to the player</em>, and a player standing at x=10.9 is genuinely
 * closer to the block at x=12 than a player at x=10.2 is; rounding the origin to a block first would
 * make the answer depend on where in the block the player happened to be standing.
 *
 * <p>Distance is measured to the centre of a block, in three dimensions, so a block directly below
 * is close in the sense a player means it. Nothing here talks to a server: the whole geometry is
 * plain arithmetic, which is what lets the search rules be unit-tested without one.
 */
public record SearchOrigin(String world, double x, double y, double z) {

    public SearchOrigin {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("world name must not be blank");
        }
    }

    /** The centre of the given block, for callers that only have block coordinates. */
    public static SearchOrigin ofBlock(String world, int blockX, int blockY, int blockZ) {
        return new SearchOrigin(world, blockX + 0.5, blockY, blockZ);
    }

    /** The block the origin is inside. */
    public int blockX() {
        return floor(x);
    }

    public int blockZ() {
        return floor(z);
    }

    /** The chunk the origin is inside. */
    public int chunkX() {
        return floor(x) >> 4;
    }

    public int chunkZ() {
        return floor(z) >> 4;
    }

    /** Squared distance from the origin to the centre of the given block. */
    public double distanceSquaredTo(int blockX, int blockY, int blockZ) {
        double dx = blockX + 0.5 - x;
        double dy = blockY + 0.5 - y;
        double dz = blockZ + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Distance in blocks from the origin to the centre of the given block. */
    public double distanceTo(int blockX, int blockY, int blockZ) {
        return Math.sqrt(distanceSquaredTo(blockX, blockY, blockZ));
    }

    /**
     * Horizontal distance from the origin to the nearest edge of a chunk's rectangle.
     *
     * <p>This is the search's lower bound: no block inside that chunk can be closer than this,
     * because a three-dimensional distance is never shorter than its horizontal part. The stopping
     * rule is built on it - once the best block found is closer than this for every chunk left to
     * inspect, that block is the answer and the search can end.
     */
    public double horizontalDistanceToChunk(int chunkX, int chunkZ) {
        double minX = (double) (chunkX << 4);
        double minZ = (double) (chunkZ << 4);
        double dx = gap(x, minX, minX + ChunkCandidate.CHUNK_SIZE);
        double dz = gap(z, minZ, minZ + ChunkCandidate.CHUNK_SIZE);
        return Math.hypot(dx, dz);
    }

    private static double gap(double coordinate, double fromInclusive, double toExclusive) {
        if (coordinate < fromInclusive) {
            return fromInclusive - coordinate;
        }
        if (coordinate >= toExclusive) {
            return coordinate - toExclusive;
        }
        return 0.0;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
