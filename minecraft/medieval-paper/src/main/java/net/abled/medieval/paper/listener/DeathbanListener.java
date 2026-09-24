package net.abled.medieval.paper.listener;

import net.abled.medieval.core.deathban.DeathbanEntry;
import net.abled.medieval.core.deathban.DeathbanService;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Records a deathban when a player dies and ends the session that produced it.
 *
 * <h2>Threads</h2>
 * {@link PlayerDeathEvent} is fired on the tick thread, so the write below is a single-row statement
 * issued from there: it has to be finished before the player respawns, and the storage layer
 * serialises access, so making it asynchronous would only move the cost to another thread while
 * adding a window in which the death is not yet recorded. WAL journalling keeps it sub-millisecond.
 *
 * <h2>Why the event priority matters</h2>
 * {@code MONITOR} plus {@code ignoreCancelled} means the ban is written only for deaths that other
 * plugins did not cancel (protection zones, arena plugins), and it is written after every other
 * listener has had its say.
 */
public final class DeathbanListener implements Listener {

    private final DeathbanService deathbans;
    private final MessageRenderer renderer;
    private final Logger logger;

    public DeathbanListener(DeathbanService deathbans, MessageRenderer renderer, Logger logger) {
        this.deathbans = Objects.requireNonNull(deathbans, "deathbans");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Instant deathAt = Instant.now();

        Optional<DeathbanEntry> created = deathbans.ban(
                player.getUniqueId(), causeOf(player), killerOf(player), deathAt);
        if (created.isEmpty()) {
            // Deathban is switched off in config.yml: nothing is recorded, nothing is kicked.
            return;
        }

        DeathbanEntry ban = created.get();
        // The row is committed by now, so disconnecting here only ends the session - it is not what
        // enforces the ban. Reconnecting goes through the pre-login gate, which reads the database.
        player.kick(renderer.renderWithPrefix("deathban-kick",
                Map.of("remaining", TimeFormat.humanize(ban.remaining(deathAt)))));
        logger.info(player.getName() + " died (" + ban.cause() + " by " + ban.killer()
                + ") and is banished for another " + TimeFormat.humanize(ban.remaining(deathAt)));
    }

    /** Readable cause of death; unknown causes fall back to the enum name rather than guessing. */
    private static String causeOf(Player player) {
        EntityDamageEvent damage = player.getLastDamageCause();
        if (damage == null) {
            // No damage event at all: /kill, void damage from a plugin, or a scripted death.
            return DeathbanEntry.UNKNOWN;
        }
        return switch (damage.getCause()) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> "combat";
            case PROJECTILE -> "projectile";
            case FALL, FLY_INTO_WALL -> "fall";
            case DROWNING -> "drowning";
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> "fire";
            case STARVATION -> "starvation";
            case SUFFOCATION -> "suffocation";
            case VOID -> "void";
            case POISON, MAGIC, WITHER, DRAGON_BREATH -> "sorcery";
            default -> damage.getCause().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static String killerOf(Player player) {
        Player killer = player.getKiller();
        return killer == null ? DeathbanEntry.UNKNOWN : killer.getName();
    }
}
