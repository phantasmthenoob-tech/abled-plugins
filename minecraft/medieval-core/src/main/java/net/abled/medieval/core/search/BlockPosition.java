package net.abled.medieval.core.search;

/** A block position in the world, as a search result. Server API free, like the rest of the search. */
public record BlockPosition(int x, int y, int z) {

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
