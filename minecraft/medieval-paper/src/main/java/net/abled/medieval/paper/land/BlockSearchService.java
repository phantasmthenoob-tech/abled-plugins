package net.abled.medieval.paper.land;

import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.search.BlockPosition;
import net.abled.medieval.core.search.BlockSearchSession;
import net.abled.medieval.core.search.ChunkBlockScanner;
import net.abled.medieval.core.search.ChunkBlocks;
import net.abled.medieval.core.search.ChunkCandidate;
import net.abled.medieval.core.search.SearchOrigin;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs {@code /land search}: a budgeted walk outwards from a player, looking for the closest block of
 * one kind and following it as far as the configured ceiling.
 *
 * <h2>The shape of the problem</h2>
 * "The closest block, no matter how far" cannot be answered by a loop. The chunk holding the answer
 * may be thousands of blocks away, so it has to be loaded, and loading it may mean reading the world
 * from disk - none of which fits in a tick, and none of which can run on the tick thread. It also
 * cannot be answered by looking at loaded chunks only, because the answer is usually outside them.
 *
 * <p>So the search is spread over ticks. Every tick each running search takes a bounded slice of the
 * world - a few chunks, whichever ones are nearest first - and the walk itself is decided by
 * {@link BlockSearchSession}, which owns the ordering, the stopping rule and the result. This class is
 * the I/O half: it loads chunks, takes a snapshot, scans it off the tick thread and hands the answer
 * back.
 *
 * <h2>Budget</h2>
 * Work per tick is capped twice over, by {@code land.search.chunks-per-tick}: at most that many chunk
 * reads or loads may be in flight for one search, and one tick may consider at most
 * {@code chunks-per-tick * 16} candidates at all. The second number exists because most of a map has
 * never been generated, and checking a chunk for mere existence is orders of magnitude cheaper than
 * reading it - a single shared budget would either crawl through unexplored terrain or spike the tick
 * while reading real chunks.
 *
 * <h2>Terrain that does not exist</h2>
 * A chunk that has never been generated holds no blocks, so it is skipped, and only
 * {@code getChunkAtAsync(..., false)} - never generate - is used to load one. A search therefore
 * answers "the closest block of this kind, in the world as it has been generated", and never rewrites
 * the world to answer a question about it. That distinction matters on a server where generation is
 * expensive and irreversible, so the not-found message says how many chunks were skipped for it.
 *
 * <h2>Threads</h2>
 * Every field of this class and every method of {@link BlockSearchSession} is touched on the tick
 * thread only. The one thing that leaves it is the block scan, which is a pure function of an
 * immutable snapshot and an origin, and whose result comes back through
 * {@link MedievalScheduler#runSync}. The scheduler's {@code runSync} runs inline when the caller is
 * already the tick thread, so this is correct whether or not the server completes a chunk future on
 * the main thread.
 */
public final class BlockSearchService {

    /** A search advances once per tick, so progress is visible without a burst of work. */
    private static final Duration TICK_PERIOD = Duration.ofMillis(50L);

    private final MedievalScheduler scheduler;
    private final MessageRenderer renderer;
    private final Supplier<MedievalSettings> settings;
    private final Logger logger;

    /** At most one search per player. Only ever touched on the tick thread. */
    private final Map<UUID, Running> active = new HashMap<>();

    public BlockSearchService(MedievalScheduler scheduler, MessageRenderer renderer,
                              Supplier<MedievalSettings> settings, Logger logger) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Starts the tick task that drives every search. Called once, at startup. */
    public void start() {
        scheduler.runSyncRepeating(this::tick, TICK_PERIOD, TICK_PERIOD);
    }

    /** Whether the command is switched on; re-read per invocation, so a reload applies immediately. */
    public boolean isEnabled() {
        return settings.get().land().search().enabled();
    }

    /**
     * Starts a search for one player.
     *
     * <p>The limits are captured here and belong to the search: a reload changes the next search, not
     * one that is already walking, so its progress and its ceiling cannot change underneath it.
     *
     * @param requestedRadius how far the player asked to look, or empty for the configured ceiling
     */
    public void start(Player player, Material block, OptionalInt requestedRadius) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(block, "block");

        MedievalSettings.Land.Search limits = settings.get().land().search();
        if (!limits.enabled()) {
            renderer.send(player, "land-search-disabled", true);
            return;
        }

        Running existing = active.get(player.getUniqueId());
        if (existing != null) {
            renderer.send(player, "land-search-already-running", progress(existing), true);
            return;
        }

        // A player asking for more than the server allows gets the server's ceiling, not a refusal:
        // they asked for "as far as possible" and that is what the ceiling is.
        int radius = requestedRadius.isPresent()
                ? Math.min(requestedRadius.getAsInt(), limits.maxRadiusBlocks())
                : limits.maxRadiusBlocks();

        Location location = player.getLocation();
        SearchOrigin origin = new SearchOrigin(player.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
        Running run = new Running(player.getUniqueId(), player.getWorld(), block,
                new BlockSearchSession(origin, radius), limits);
        active.put(player.getUniqueId(), run);

        renderer.send(player, "land-search-started", Map.of(
                "block", blockName(block),
                "radius", Integer.toString(radius)), true);
        logger.info(player.getName() + " is searching for the closest " + blockName(block)
                + " within " + radius + " blocks of " + describe(origin));
    }

    /** Reports a player's own search, or that they have none. */
    public void status(Player player) {
        Running run = active.get(player.getUniqueId());
        if (run == null) {
            renderer.send(player, "land-search-nothing-running", true);
            return;
        }
        renderer.send(player, "land-search-progress", progress(run), true);
    }

    /**
     * Stops a player's search.
     *
     * <p>The reply is sent here rather than from the tick, because the player asked and deserves an
     * answer now; the bookkeeping entry is removed once the chunk reads already in flight have drained,
     * which is what keeps the tick thread the only place that touches a search.
     */
    public void cancel(Player player) {
        Running run = active.get(player.getUniqueId());
        if (run == null) {
            renderer.send(player, "land-search-nothing-running", true);
            return;
        }

        run.session.cancel();
        renderer.send(player, "land-search-cancelled", progress(run), true);
        logger.info(player.getName() + " stopped their search for " + blockName(run.block)
                + " after reading " + run.session.chunksRead() + " chunk(s)");
    }

    // ------------------------------------------------------------------ driving

    private void tick() {
        if (active.isEmpty()) {
            return;
        }
        for (Running run : List.copyOf(active.values())) {
            drive(run);
        }
    }

    private void drive(Running run) {
        Player player = Bukkit.getPlayer(run.playerId);
        if (player == null) {
            // A search belongs to the session that started it: a player who logs out does not keep
            // chunk loads running on their behalf.
            run.session.cancel();
            active.remove(run.playerId);
            logger.info("Stopped the block search of " + run.playerId + " because the player left");
            return;
        }

        if (run.session.isSearching()) {
            if (Duration.ofNanos(System.nanoTime() - run.startedAtNanos).compareTo(run.maxDuration) >= 0) {
                run.session.timeOut();
            } else {
                advance(run);
            }
        }

        // Reported only with nothing outstanding, so the numbers in the message are final and no
        // result can arrive after the player has been told the answer.
        if (!run.session.isSearching() && run.inFlight == 0) {
            report(run, player);
        }
    }

    private void advance(Running run) {
        int budget = run.candidateBudget;
        while (budget > 0 && run.inFlight < run.chunkBudget && run.session.isSearching()) {
            budget--;

            if (run.session.closestIsCertain()) {
                run.session.finish();
                return;
            }

            Optional<ChunkCandidate> candidate = run.session.nextCandidate();
            if (candidate.isEmpty()) {
                if (run.inFlight == 0) {
                    run.session.finish();
                }
                return;
            }
            dispatch(run, candidate.get());
        }
    }

    private void dispatch(Running run, ChunkCandidate candidate) {
        int chunkX = candidate.chunkX();
        int chunkZ = candidate.chunkZ();

        if (!run.world.isChunkGenerated(chunkX, chunkZ)) {
            // No terrain, so no block: skipping is not a compromise, it is the only honest answer.
            run.session.recordSkipped();
            return;
        }

        if (run.world.isChunkLoaded(chunkX, chunkZ)) {
            read(run, run.world.getChunkAt(chunkX, chunkZ));
            return;
        }

        run.inFlight++;
        run.world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, failure) ->
                scheduler.runSync(() -> {
                    run.inFlight--;
                    if (failure != null) {
                        logger.log(Level.WARNING, "Could not load chunk " + chunkX + ", " + chunkZ
                                + " for " + blockName(run.block) + " search", failure);
                        run.session.recordSkipped();
                        return;
                    }
                    if (chunk == null) {
                        // Asked for without generation, and there is nothing there to load.
                        run.session.recordSkipped();
                        return;
                    }
                    read(run, chunk);
                }));
    }

    /**
     * Copies the chunk and scans the copy off the tick thread.
     *
     * <p>The snapshot is taken here, on the tick thread, because that is what Bukkit requires; the
     * expensive part - reading every block of the chunk - happens on the scheduler's background thread
     * so a search cannot show up as a tick spike.
     */
    private void read(Running run, Chunk chunk) {
        ChunkSnapshot snapshot;
        try {
            // Block types only: a search reads no biome, no light and no per-column height map, and each
            // of those would be another copy of the chunk.
            snapshot = chunk.getChunkSnapshot(false, false, false, false);
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "Could not snapshot chunk " + chunk.getX() + ", " + chunk.getZ()
                    + " for " + blockName(run.block) + " search", failure);
            run.session.recordSkipped();
            return;
        }

        run.inFlight++;
        ChunkBlocks blocks = new ChunkSnapshotBlocks(snapshot,
                run.world.getMinHeight(), run.world.getMaxHeight(), run.block);
        SearchOrigin origin = run.session.origin();
        scheduler.runAsync(() -> {
            Optional<BlockPosition> nearest = ChunkBlockScanner.nearest(blocks, origin);
            scheduler.runSync(() -> {
                run.inFlight--;
                run.session.record(nearest);
            });
        });
    }

    private void report(Running run, Player player) {
        // Removing first makes the report single-shot: a straggling callback cannot produce a second
        // message, and the player can start a new search the moment this one is answered.
        if (!active.remove(run.playerId, run)) {
            return;
        }

        BlockSearchSession session = run.session;
        switch (session.outcome()) {
            case FOUND -> {
                BlockPosition found = session.found().orElseThrow();
                renderer.send(player, "land-search-found", found(run, found), true);
                logger.info(blockName(run.block) + " found for " + player.getName() + " at " + found
                        + " (" + Math.round(session.foundDistance()) + " blocks, "
                        + session.chunksRead() + " chunk(s) read)");
            }
            case TIMED_OUT -> {
                // A match found before the limit is still a real block at a real distance, but the walk
                // that would have proved it the closest one did not finish - so it is reported as a
                // candidate rather than as the answer.
                Optional<BlockPosition> best = session.found();
                if (best.isPresent()) {
                    renderer.send(player, "land-search-stopped-early", found(run, best.get()), true);
                } else {
                    renderer.send(player, "land-search-timed-out", progress(run), true);
                }
                logger.info("Block search for " + player.getName() + " hit the time limit after "
                        + session.chunksRead() + " chunk(s)");
            }
            case NOT_FOUND -> {
                renderer.send(player, "land-search-not-found", progress(run), true);
                logger.info("No " + blockName(run.block) + " for " + player.getName() + " within "
                        + session.radiusLimitBlocks() + " blocks (" + session.chunksRead() + " chunk(s) read, "
                        + session.chunksSkipped() + " not generated)");
            }
            // A cancelled search was already answered by the command that cancelled it.
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Progress plus the coordinates of the block the message is about. */
    private Map<String, String> found(Running run, BlockPosition position) {
        Map<String, String> placeholders = new HashMap<>(progress(run));
        placeholders.put("x", Integer.toString(position.x()));
        placeholders.put("y", Integer.toString(position.y()));
        placeholders.put("z", Integer.toString(position.z()));
        placeholders.put("distance", Long.toString(Math.round(run.session.foundDistance())));
        return Map.copyOf(placeholders);
    }

    private Map<String, String> progress(Running run) {
        BlockSearchSession session = run.session;
        return Map.of(
                "block", blockName(run.block),
                "radius", Long.toString(Math.round(session.radiusReached())),
                "limit", Integer.toString(session.radiusLimitBlocks()),
                "chunks", Integer.toString(session.chunksRead()),
                "skipped", Integer.toString(session.chunksSkipped()),
                "elapsed", TimeFormat.humanize(Duration.ofNanos(System.nanoTime() - run.startedAtNanos)));
    }

    private static String blockName(Material block) {
        return block.getKey().getKey();
    }

    private static String describe(SearchOrigin origin) {
        return origin.world() + " " + origin.blockX() + ", " + (int) origin.y() + ", " + origin.blockZ();
    }

    /** One running search: what it is looking for, how far it may go, and what is outstanding. */
    private static final class Running {

        private final UUID playerId;
        private final World world;
        private final Material block;
        private final BlockSearchSession session;
        private final int chunkBudget;
        private final int candidateBudget;
        private final Duration maxDuration;
        private final long startedAtNanos = System.nanoTime();

        /** Chunk reads and loads outstanding; only the tick thread changes it. */
        private int inFlight;

        private Running(UUID playerId, World world, Material block, BlockSearchSession session,
                        MedievalSettings.Land.Search limits) {
            this.playerId = playerId;
            this.world = world;
            this.block = block;
            this.session = session;
            this.chunkBudget = limits.chunksPerTick();
            this.candidateBudget = limits.candidatesPerTick();
            this.maxDuration = limits.maxDuration();
        }
    }
}
