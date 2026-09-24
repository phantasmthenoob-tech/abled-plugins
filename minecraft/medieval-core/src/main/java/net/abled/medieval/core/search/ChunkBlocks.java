package net.abled.medieval.core.search;

/**
 * The block data of one chunk, as the search needs to see it.
 *
 * <p>This is the seam between the rules and the server. The core decides <em>which</em> chunks to look
 * at and what the answer is; a platform adapter supplies the blocks, and can supply them from
 * whatever is cheapest - a chunk snapshot copied on the tick thread and read on another, in the Paper
 * adapter's case.
 *
 * <p>Vertical limits come from the world rather than being assumed: a build height of -64..319 is a
 * modern overworld default, not a law, and a custom or future world may use a different range.
 */
public interface ChunkBlocks {

    /** Smallest block x coordinate inside this chunk. */
    int minBlockX();

    /** Smallest block z coordinate inside this chunk. */
    int minBlockZ();

    /** Lowest y this chunk holds, inclusive. */
    int minHeight();

    /** One past the highest y this chunk holds, exclusive. */
    int maxHeight();

    /**
     * True when a whole 16x16x16 section holds no blocks at all.
     *
     * <p>Empty sections are the common case above a surface and below bedrock, so this is what keeps a
     * full-height sweep affordable: a section that answers true is skipped without reading the 4096
     * blocks in it.
     *
     * @param sectionY section index, which is the block y divided by 16 - not a block coordinate
     */
    boolean isSectionEmpty(int sectionY);

    /**
     * True when the block at these coordinates is the one being searched for.
     *
     * @param localX 0-15 within the chunk
     * @param y      block coordinate, between {@link #minHeight()} and {@link #maxHeight()}
     * @param localZ 0-15 within the chunk
     */
    boolean matches(int localX, int y, int localZ);
}
