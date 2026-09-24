package net.abled.medieval.core.search;

import java.util.HashSet;
import java.util.Set;

/**
 * A chunk of blocks in memory, so the search rules can be tested without a server.
 *
 * <p>Every section starts out non-empty, which is the expensive case: a fake that reported every
 * section empty by default would make the scan look fast and would let a broken section range or a
 * broken empty-section check pass unnoticed. Sections are only skipped when a test says so.
 */
final class FakeChunk implements ChunkBlocks {

    private final int chunkX;
    private final int chunkZ;
    private final int minHeight;
    private final int maxHeight;
    private final Set<BlockPosition> matches = new HashSet<>();
    private final Set<Integer> emptySections = new HashSet<>();

    private int matchesCalls;
    private int emptySectionChecks;

    FakeChunk(int chunkX, int chunkZ, int minHeight, int maxHeight) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
    }

    FakeChunk withMatch(int x, int y, int z) {
        matches.add(new BlockPosition(x, y, z));
        return this;
    }

    FakeChunk withEmptySections(int... sectionYs) {
        for (int sectionY : sectionYs) {
            emptySections.add(sectionY);
        }
        return this;
    }

    /** Blocks this chunk holds, for the exhaustive scan the search is compared against. */
    Set<BlockPosition> matches() {
        return Set.copyOf(matches);
    }

    /** How many blocks were actually inspected, which is what section skipping is meant to cut. */
    int matchesCalls() {
        return matchesCalls;
    }

    int emptySectionChecks() {
        return emptySectionChecks;
    }

    @Override
    public int minBlockX() {
        return chunkX << 4;
    }

    @Override
    public int minBlockZ() {
        return chunkZ << 4;
    }

    @Override
    public int minHeight() {
        return minHeight;
    }

    @Override
    public int maxHeight() {
        return maxHeight;
    }

    @Override
    public boolean isSectionEmpty(int sectionY) {
        emptySectionChecks++;
        return emptySections.contains(sectionY);
    }

    @Override
    public boolean matches(int localX, int y, int localZ) {
        matchesCalls++;
        return matches.contains(new BlockPosition(minBlockX() + localX, y, minBlockZ() + localZ));
    }
}
