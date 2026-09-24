package net.abled.medieval.paper.catalogue;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;

/**
 * Feeds chat lines to {@link CatalogueSearch} while that player is answering a search prompt.
 *
 * <h2>Only while a prompt is waiting</h2>
 * {@link CatalogueSearch#consume} answers {@code false} for a player who has no prompt outstanding,
 * so ordinary chat is never inspected, altered or held up - this listener is a no-op for everyone
 * else on the server. A line that <em>was</em> a search is cancelled, which is what keeps a query
 * from being broadcast as chat.
 *
 * <h2>Why this event</h2>
 * {@link AsyncChatEvent}, not the deprecated {@code AsyncPlayerChatEvent}: it is the supported chat
 * event on this server version and it hands over an Adventure component. The plain-text form of that
 * component is what the matcher sees, so another plugin's chat formatting cannot change what a
 * search finds.
 *
 * <h2>Priority</h2>
 * {@code LOWEST} so the raw message is read before any formatter rewrites it, and before the message
 * is delivered. The event is asynchronous, which is where {@code CatalogueSearch} does its work;
 * everything it does to an inventory is handed back to the tick thread there.
 */
public final class CatalogueSearchListener implements Listener {

    private final CatalogueSearch search;

    public CatalogueSearchListener(CatalogueSearch search) {
        this.search = Objects.requireNonNull(search, "search");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (search.consume(event.getPlayer().getUniqueId(), message)) {
            event.setCancelled(true);
        }
    }
}
