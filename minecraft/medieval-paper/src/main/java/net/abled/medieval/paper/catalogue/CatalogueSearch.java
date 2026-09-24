package net.abled.medieval.paper.catalogue;

import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a player what they are looking for, and hands the answer to the menu they were using.
 *
 * <h2>Why chat, and not a sign or an anvil</h2>
 * Bedrock players arrive through Geyser, which does not carry the sign editor or anvil renaming
 * across. Typing into chat is the one text input every client has, so the prompt is a chat message
 * and the player's next line is captured before it broadcasts.
 *
 * <h2>Threads</h2>
 * The chat event arrives on an asynchronous thread, so this class only records the answer there and
 * hands every inventory operation back through {@link MedievalScheduler#runSync}: opening a menu is
 * server state and must happen on the tick thread. The player is looked up by UUID inside that
 * synchronous task rather than being captured, so a player who disconnects mid-prompt is simply
 * skipped instead of being touched through a stale handle.
 *
 * <h2>Expiry</h2>
 * A prompt waits {@value #TIMEOUT_SECONDS} seconds. After that the player's next chat line is
 * ordinary chat again - a prompt that silently ate a message sent ten minutes later would be worse
 * than an expired search. The deadline is also checked before the message is consumed, so an
 * abandoned prompt cannot swallow what someone was actually saying.
 */
public final class CatalogueSearch {

    /** How long a prompt waits for an answer, before the player's chat is none of our business. */
    public static final long TIMEOUT_SECONDS = 45L;

    private static final Duration TIMEOUT = Duration.ofSeconds(TIMEOUT_SECONDS);

    /** Keyword that abandons the prompt; shown to the player in the prompt text itself. */
    public static final String CANCEL = "cancel";

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final MedievalScheduler scheduler;
    private final MessageRenderer renderer;

    public CatalogueSearch(MedievalScheduler scheduler, MessageRenderer renderer) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /**
     * Closes the menu and asks the player what to search for. Must be called on the tick thread,
     * because it closes an inventory.
     */
    public void prompt(Player player, CatalogueMenu menu) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");

        // Abandoned prompts are dropped here rather than being left for the next chat line: the map
        // is tiny, and a stale entry could otherwise answer a message minutes later.
        Instant now = Instant.now();
        pending.values().removeIf(request -> request.expired(now));
        pending.put(player.getUniqueId(), new Pending(menu, now.plus(TIMEOUT)));

        player.closeInventory();
        renderer.send(player, "catalogue-search-prompt",
                Map.of("timeout", TimeFormat.humanize(TIMEOUT), "cancel", CANCEL), true);
    }

    /**
     * Offers one chat line to a waiting prompt.
     *
     * <p>Called from the asynchronous chat thread.
     *
     * @return true when the message was taken as a search, and must therefore not be broadcast
     */
    public boolean consume(UUID playerId, String message) {
        Objects.requireNonNull(playerId, "playerId");

        Pending request = pending.remove(playerId);
        if (request == null) {
            return false;
        }
        if (request.expired(Instant.now())) {
            announce(playerId, "catalogue-search-expired");
            return false;
        }

        String text = message == null ? "" : message.trim();
        if (text.isEmpty() || CANCEL.equalsIgnoreCase(text)) {
            scheduler.runSync(() -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    request.menu().open(player, false);
                    renderer.send(player, "catalogue-search-cancelled", true);
                }
            });
            return true;
        }

        scheduler.runSync(() -> search(playerId, request.menu(), text));
        return true;
    }

    private void search(UUID playerId, CatalogueMenu menu, String text) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        if (!menu.search(text)) {
            // Nothing to search for after normalisation (punctuation only, say): the menu is
            // reopened unchanged rather than showing an empty result for a query nobody made.
            menu.open(player, true);
            renderer.send(player, "catalogue-search-blank", true);
            return;
        }

        menu.open(player, false);
        renderer.send(player, "catalogue-search-found", Map.of(
                "query", menu.query(),
                "count", Integer.toString(menu.resultCount())), true);
    }

    private void announce(UUID playerId, String key) {
        scheduler.runSync(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                renderer.send(player, key, true);
            }
        });
    }

    private record Pending(CatalogueMenu menu, Instant deadline) {

        boolean expired(Instant now) {
            return !now.isBefore(deadline);
        }
    }
}
