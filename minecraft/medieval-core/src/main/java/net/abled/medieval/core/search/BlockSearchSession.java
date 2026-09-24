package net.abled.medieval.core.search;

import java.util.Objects;
import java.util.Optional;

/**
 * One search for the closest block of a kind: what is left to inspect, and the closest block found so
 * far.
 *
 * <p>State lives here rather than in the platform driver so that the rules - when to stop, which block
 * wins, what the answer is when nothing is found - are the same on every platform and can be tested
 * without a server. The driver does the I/O and the waiting; this decides.
 *
 * <h2>Mutating methods never fail and never throw</h2>
 * A driver that looks up a chunk over several ticks can have work outstanding when the search ends:
 * it is cancelled, or it was decided by the stopping rule while a chunk was still being read. Every
 * mutating method therefore ignores calls once the search is over, so a late result cannot overwrite
 * an answer that was already reported.
 *
 * <h2>Single-threaded</h2>
 * Every method here must be called from one thread - the driver's - because the session has no
 * synchronisation. The Paper driver calls it from the tick thread only, including for results that
 * arrive from a background scan.
 */
public final class BlockSearchSession {

    /** How a search ended. */
    public enum Outcome {

        /** Still running. */
        SEARCHING,
        /** Finished with a block that is certainly the closest one. */
        FOUND,
        /** Every chunk within the radius limit was read and none held the block. */
        NOT_FOUND,
        /** Stopped on the time limit before the radius was covered. */
        TIMED_OUT,
        /** Stopped on request. */
        CANCELLED
    }

    private final SearchCursor cursor;
    private final int radiusLimitBlocks;

    private Outcome outcome = Outcome.SEARCHING;
    private BlockPosition best;
    private double bestDistanceSquared = Double.POSITIVE_INFINITY;
    private int chunksRead;
    private int chunksSkipped;
    private int candidatesTaken;

    /**
     * @param origin           where the search starts
     * @param radiusLimitBlocks how far out it may look; even an unbounded request has a limit, because
     *                          a search for a block that does not exist in this world would otherwise
     *                          never end
     */
    public BlockSearchSession(SearchOrigin origin, int radiusLimitBlocks) {
        this.cursor = new SearchCursor(origin, radiusLimitBlocks);
        this.radiusLimitBlocks = radiusLimitBlocks;
    }

    // ------------------------------------------------------------------ driving

    /**
     * The next chunk to inspect, or empty when nothing within the radius is left.
     *
     * <p>The caller must inspect what it is given and report the result back through
     * {@link #record(Optional)} (or {@link #recordSkipped()} for a chunk it will not read at all),
     * otherwise the counters and the answer misrepresent the search.
     */
    public Optional<ChunkCandidate> nextCandidate() {
        if (outcome != Outcome.SEARCHING) {
            return Optional.empty();
        }
        Optional<ChunkCandidate> candidate = cursor.next();
        candidate.ifPresent(ignored -> candidatesTaken++);
        return candidate;
    }

    /**
     * True when the closest block found so far cannot be beaten by anything left to inspect.
     *
     * <p>This is the rule that ends a search early without weakening it: it compares the best block
     * found against the lower bound of every chunk still queued, so it only ever fires when the answer
     * is already certain. False while nothing has been found.
     */
    public boolean closestIsCertain() {
        if (best == null) {
            return false;
        }
        double floor = cursor.remainingLowerBound();
        return bestDistanceSquared <= floor * floor;
    }

    /**
     * Records what a chunk held.
     *
     * @param nearest the closest matching block in the chunk that was just read, or empty when it held
     *                none
     */
    public void record(Optional<BlockPosition> nearest) {
        Objects.requireNonNull(nearest, "nearest");
        if (outcome != Outcome.SEARCHING) {
            return;
        }

        chunksRead++;
        if (nearest.isEmpty()) {
            return;
        }

        BlockPosition position = nearest.get();
        double distanceSquared = cursor.origin().distanceSquaredTo(position.x(), position.y(), position.z());
        if (distanceSquared < bestDistanceSquared) {
            bestDistanceSquared = distanceSquared;
            best = position;
        }
    }

    /**
     * Records a chunk that was never read: terrain that does not exist yet, or a chunk that could not
     * be loaded. Neither can hold a block, so skipping one does not weaken the answer - but the count
     * is kept, because "nothing found" reads very differently when 40 chunks were read and 40,000 were
     * never generated.
     */
    public void recordSkipped() {
        if (outcome != Outcome.SEARCHING) {
            return;
        }
        chunksSkipped++;
    }

    /**
     * Ends the search, reporting {@link Outcome#FOUND} when a block was found.
     *
     * <p>Only correct once nothing is left to inspect and no chunk read is outstanding - the driver
     * calls it on exactly those terms.
     *
     * @return the outcome, which is the existing one when the search had already ended
     */
    public Outcome finish() {
        if (outcome != Outcome.SEARCHING) {
            return outcome;
        }
        outcome = best == null ? Outcome.NOT_FOUND : Outcome.FOUND;
        return outcome;
    }

    /** Ends the search on the time limit, keeping whatever was found so far. */
    public void timeOut() {
        if (outcome == Outcome.SEARCHING) {
            outcome = Outcome.TIMED_OUT;
        }
    }

    /** Ends the search on request. */
    public void cancel() {
        if (outcome == Outcome.SEARCHING) {
            outcome = Outcome.CANCELLED;
        }
    }

    // ------------------------------------------------------------------ state

    public Outcome outcome() {
        return outcome;
    }

    public boolean isSearching() {
        return outcome == Outcome.SEARCHING;
    }

    /** The closest matching block found, or empty when none was. */
    public Optional<BlockPosition> found() {
        return Optional.ofNullable(best);
    }

    /** Distance in blocks to {@link #found()}, or {@link Double#NaN} when nothing was found. */
    public double foundDistance() {
        return best == null ? Double.NaN : Math.sqrt(bestDistanceSquared);
    }

    /** Chunks whose block data was read. */
    public int chunksRead() {
        return chunksRead;
    }

    /** Chunks that were passed over because they hold no terrain. */
    public int chunksSkipped() {
        return chunksSkipped;
    }

    /** Chunks handed out by the cursor, read or skipped. */
    public int candidatesTaken() {
        return candidatesTaken;
    }

    /** How far out the search has reached, in blocks. */
    public double radiusReached() {
        return Math.min(cursor.radiusReached(), radiusLimitBlocks);
    }

    /** How far out the search was allowed to look, in blocks. */
    public int radiusLimitBlocks() {
        return radiusLimitBlocks;
    }

    public SearchOrigin origin() {
        return cursor.origin();
    }
}
