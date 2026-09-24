package net.abled.medieval.paper.listener;

import net.abled.medieval.core.deathban.AccessDecision;
import net.abled.medieval.core.deathban.DeathbanService;
import net.abled.medieval.core.player.PlayerIdentityService;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The login gate: enforces stored deathbans and records the player profile before the player is
 * ever added to the server.
 *
 * <h2>Threads</h2>
 * {@link AsyncPlayerPreLoginEvent} is fired off the tick thread, which is exactly where the database
 * work belongs: a login never blocks the server, and the decision is made before joining. The rule
 * itself is in the core ({@link DeathbanService#checkLogin}); this class only renders the outcome.
 *
 * <h2>Failing closed</h2>
 * If the database cannot be read the login is refused rather than allowed. Letting players in
 * because storage is unreachable would hand a banned player exactly the bypass the deathban exists
 * to prevent.
 */
public final class LoginGateListener implements Listener {

    private final DeathbanService deathbans;
    private final PlayerIdentityService identities;
    private final MessageRenderer renderer;
    private final Logger logger;

    public LoginGateListener(DeathbanService deathbans, PlayerIdentityService identities,
                            MessageRenderer renderer, Logger logger) {
        this.deathbans = Objects.requireNonNull(deathbans, "deathbans");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID playerId = event.getUniqueId();
        String name = event.getName();

        try {
            AccessDecision decision = deathbans.checkLogin(playerId);
            if (decision.denied()) {
                String remaining = TimeFormat.humanize(decision.remaining());
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                        renderer.renderWithPrefix("deathban-join-denied", Map.of("remaining", remaining)));
                logger.info(name + " (" + playerId + ") was refused login: banished for another " + remaining);
                return;
            }

            // Profile recording is independent of the deathban: it is what lets staff look a player
            // up by name later. Ownership still uses the UUID, never the name.
            try {
                identities.recordLogin(playerId, name, Instant.now());
            } catch (IllegalArgumentException unusableName) {
                // An unusable name must not cost the player their login: access was already decided
                // above, and the profile only exists to make staff lookups possible.
                logger.warning("Not storing a profile for " + playerId + ": " + unusableName.getMessage());
            }
        } catch (RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not check the deathban of " + name + " (" + playerId + ")", failure);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    renderer.renderWithPrefix("storage-unavailable"));
        }
    }
}
