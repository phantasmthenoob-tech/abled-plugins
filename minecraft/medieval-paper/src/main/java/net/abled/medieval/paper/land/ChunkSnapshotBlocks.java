package net.abled.medieval.paper.land;

import net.abled.medieval.core.search.ChunkBlocks;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.util.Objects;

/**
 * {@link ChunkBlocks} over a Bukkit {@link ChunkSnapshot}: the adapter that lets the core's scanner
 * read a chunk it has no Bukkit dependency for.
 *
 * <p>A snapshot is the supported way to read blocks away from the tick thread - Bukkit describes it
 * as a clean, efficient copy made to be handed to another thread - and that is exactly what a search
 * needs, because a full-height chunk read is far too much work to do inside a tick.
 *
 * <p>The world's height range is passed in rather than inferred: a snapshot has no height accessors of
 * its own, and {@code -64..319} is a default rather than a law. A world with a custom range would
 * otherwise be searched only to y=319, or read past its own ceiling.
 */
final class ChunkSnapshotBlocks implements ChunkBlocks {

    private final ChunkSnapshot snapshot;
    private final int minHeight;
    private final int maxHeight;
    private final Material target;

    ChunkSnapshotBlocks(ChunkSnapshot snapshot, int minHeight, int maxHeight, Material target) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.target = Objects.requireNonNull(target, "target");
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
    }

    @Override
    public int minBlockX() {
        return snapshot.getX() << 4;
    }

    @Override
    public int minBlockZ() {
        return snapshot.getZ() << 4;
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
        // Sections outside the world's range are never asked about: the scanner derives its range from
        // minHeight/maxHeight above.
        return snapshot.isSectionEmpty(sectionY);
    }

    @Override
    public boolean matches(int localX, int y, int localZ) {
        // Compares the material only, never its block state: a slab facing another way, a stair built
        // the other way round and a lit furnace are all still that block.
        return snapshot.getBlockType(localX, y, localZ) == target;
    }
}
