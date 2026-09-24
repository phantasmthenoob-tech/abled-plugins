package net.abled.medieval.core.search;

/**
 * One chunk the search may inspect, together with the closest a block inside it could possibly be.
 *
 * @param chunkX     chunk coordinate on the x axis
 * @param chunkZ     chunk coordinate on the z axis
 * @param lowerBound distance in blocks from the search origin to the nearest edge of this chunk;
 *                   nothing inside it can be closer than this
 */
public record ChunkCandidate(int chunkX, int chunkZ, double lowerBound) {

    /** Blocks per chunk edge, on both horizontal axes. */
    public static final int CHUNK_SIZE = 16;

    /** Smallest block x coordinate inside this chunk. */
    public int minBlockX() {
        return chunkX << 4;
    }

    /** Smallest block z coordinate inside this chunk. */
    public int minBlockZ() {
        return chunkZ << 4;
    }
}
