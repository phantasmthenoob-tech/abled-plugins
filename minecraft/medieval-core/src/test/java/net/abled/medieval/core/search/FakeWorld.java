package net.abled.medieval.core.search;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A world in memory: the chunks that have been generated, and nothing else.
 *
 * <p>Absent chunks are the point of it - most of a real map has never been generated, and the search
 * has to skip those rather than fall over or pretend they are empty-but-present.
 */
final class FakeWorld {

    private final int minHeight;
    private final int maxHeight;
    private final Map<Key, FakeChunk> chunks = new HashMap<>();

    FakeWorld(int minHeight, int maxHeight) {
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
    }

    /** Generates a fresh chunk at these coordinates with the world's height range. */
    FakeChunk generate(int chunkX, int chunkZ) {
        return new FakeChunk(chunkX, chunkZ, minHeight, maxHeight);
    }

    FakeWorld with(FakeChunk chunk) {
        chunks.put(new Key(chunk.minBlockX() >> 4, chunk.minBlockZ() >> 4), chunk);
        return this;
    }

    Optional<FakeChunk> at(int chunkX, int chunkZ) {
        return Optional.ofNullable(chunks.get(new Key(chunkX, chunkZ)));
    }

    private record Key(int chunkX, int chunkZ) {
    }
}
