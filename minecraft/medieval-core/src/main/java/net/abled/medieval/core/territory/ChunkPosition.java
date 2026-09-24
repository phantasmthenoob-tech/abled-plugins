package net.abled.medieval.core.territory;

import java.util.Objects;

/**
 * Server-side identity of a chunk in a specific world.
 *
 * <p>Territory, claims and siege structures are all keyed on this value. It is deliberately not
 * packed into a single long: the world (dimension key) must stay part of the key so a claim can
 * never leak between dimensions, and {@link #storageKey()} gives a stable database key.
 */
public record ChunkPosition(String world, int chunkX, int chunkZ) {

    public static final char SEPARATOR = ';';

    public ChunkPosition {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("world name must not be blank");
        }
        if (world.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("world name must not contain '" + SEPARATOR + "': " + world);
        }
    }

    /** Chunk containing the given block coordinates. Handles negative coordinates correctly. */
    public static ChunkPosition ofBlock(String world, int blockX, int blockZ) {
        return new ChunkPosition(world, blockX >> 4, blockZ >> 4);
    }

    public int minBlockX() {
        return chunkX << 4;
    }

    public int minBlockZ() {
        return chunkZ << 4;
    }

    /** True when the block coordinates fall inside this chunk. */
    public boolean containsBlock(int blockX, int blockZ) {
        return (blockX >> 4) == chunkX && (blockZ >> 4) == chunkZ;
    }

    /** Stable key for persistence and diagnostics, for example {@code world;3;-7}. */
    public String storageKey() {
        return world + SEPARATOR + chunkX + SEPARATOR + chunkZ;
    }

    /** Parses a key produced by {@link #storageKey()}. */
    public static ChunkPosition parse(String storageKey) {
        Objects.requireNonNull(storageKey, "storageKey");
        String[] parts = storageKey.split(String.valueOf(SEPARATOR));
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed chunk key: '" + storageKey + "'");
        }
        try {
            return new ChunkPosition(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("malformed chunk key: '" + storageKey + "'", failure);
        }
    }
}
