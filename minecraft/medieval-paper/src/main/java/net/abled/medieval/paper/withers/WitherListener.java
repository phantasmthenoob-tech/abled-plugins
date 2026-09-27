package net.abled.medieval.paper.withers;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * Every event the wither systems react to, in one listener.
 *
 * <p>The rules here are thin plumbing over the two managers: clean up on any way a rider can
 * leave a mount, forward the owner's attacks to {@link SkeletonManager#assignTarget}, and refuse
 * damage or targeting that would turn a summoned skeleton against its own side. The decisions
 * about <em>what</em> to do live in the managers; this class decides <em>when</em>.
 */
public final class WitherListener implements Listener {

    private final Plugin plugin;
    private final WitherMountManager mounts;
    private final SkeletonManager skeletons;

    public WitherListener(Plugin plugin, WitherMountManager mounts, SkeletonManager skeletons) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mounts = Objects.requireNonNull(mounts, "mounts");
        this.skeletons = Objects.requireNonNull(skeletons, "skeletons");
    }

    // ------------------------------------------------------------------
    // Mount cleanup: any way a rider can leave must not leave entities behind
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        mounts.destroy(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        mounts.destroy(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        mounts.destroy(event.getEntity().getUniqueId(), false);
    }

    /**
     * A dismount - shift-click, or the vehicle vanishing under the rider - tears the mount down.
     *
     * <p>Only plugin control vehicles are matched, and the stand is marker-sized and invisible,
     * so a player cannot ride one of ours by accident; the destroy is idempotent anyway.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getDismounted() instanceof ArmorStand stand
                && mounts.isControlVehicle(stand)) {
            mounts.destroy(player.getUniqueId(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        // Any mount whose vehicle was in that world loses its entity on unload; the tick loop
        // would tear it down, but doing it here is immediate and avoids one tick of a rider
        // sitting on a removed stand.
        for (Player player : event.getWorld().getPlayers()) {
            mounts.destroy(player.getUniqueId(), false);
        }
    }

    // ------------------------------------------------------------------
    // Mount safety: nobody else may break or ride a plugin vehicle or skull
    // ------------------------------------------------------------------

    /** A mount's entities are invulnerable already, but a cancelled event is the hard guarantee. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMountDamaged(EntityDamageByEntityEvent event) {
        if (mounts.isControlVehicle(event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        // The rider of a mount skull is the owner; nobody else can be. When the skull's explosion
        // would hurt its own rider, that is the cost of flying into a wall - left to vanilla.
    }

    // ------------------------------------------------------------------
    // Skull explosions: shape the yield to the configured values
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSkullExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof WitherSkull skull)) {
            return;
        }
        String stamped = skull.getPersistentDataContainer().get(mounts.typeKey(),
                org.bukkit.persistence.PersistentDataType.STRING);
        if (stamped == null) {
            return;
        }
        boolean charged = stamped.equals("charged");
        if (!mounts.skullShouldExplode(charged)) {
            event.setCancelled(true);
            return;
        }
        // Clear vanilla's block list and apply the configured yield through the damage, so the
        // configured power is exact rather than whatever the projectile spawned with.
        event.blockList().clear();
        float power = (float) mounts.explosionPower(charged);
        if (power <= 0.0f) {
            event.setCancelled(true);
            return;
        }
        event.getEntity().getWorld().createExplosion(
                event.getLocation(), power, false, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSkullBlockExplode(BlockExplodeEvent event) {
        // Block explosions are not skulls; kept out of the way intentionally.
    }

    // ------------------------------------------------------------------
    // Skeletons: targeting, owner immunity, friendly fire
    // ------------------------------------------------------------------

    /**
     * The core mechanic: the owner's attack is the command.
     *
     * <p>Any entity the owner damages becomes the target of every skeleton they own - mob,
     * animal, or player. The victim is the exact entity from the event; no nearest-enemy search.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (victim.getUniqueId().equals(attacker.getUniqueId())) {
            return;
        }
        // Attacking one of your own skeletons is not a target command; it is also refused below,
        // so the skeleton neither takes the order nor retaliates.
        if (skeletons.isOwnedBy(victim, attacker.getUniqueId())) {
            return;
        }
        skeletons.assignTarget(attacker.getUniqueId(), victim);
    }

    /**
     * Owner immunity and owned-on-owned friendly fire, enforced at the damage layer.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSkeletonDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof WitherSkeleton)
                && !(event.getDamager() instanceof org.bukkit.entity.Wither)) {
            return;
        }
        UUID owner = skeletons.ownerOf(event.getDamager());
        if (owner == null) {
            return;
        }
        // A summoned creature never damages its owner.
        if (event.getEntity().getUniqueId().equals(owner)) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Mob summon && summon.getTarget() != null
                    && summon.getTarget().getUniqueId().equals(owner)) {
                summon.setTarget(null);
            }
            return;
        }
        // Owned-on-owned violence is off unless the config allows it.
        if (!skeletons.isOwnedBy(event.getEntity(), owner)) {
            return;
        }
        if (event.getEntity() instanceof WitherSkeleton
                || event.getEntity() instanceof org.bukkit.entity.Wither) {
            event.setCancelled(true);
        }
    }

    /**
     * The belt to the damage event's braces: if vanilla AI ever picks the owner or a fellow owned
     * skeleton as a target for a summoned skeleton, the choice is refused before it becomes an
     * attack.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSkeletonTarget(EntityTargetEvent event) {
        if (!(event.getEntity() instanceof WitherSkeleton)
                && !(event.getEntity() instanceof org.bukkit.entity.Wither)) {
            return;
        }
        Mob summon = (Mob) event.getEntity();
        UUID owner = skeletons.ownerOf(summon);
        if (owner == null) {
            return;
        }
        if (event.getTarget() instanceof LivingEntity candidate) {
            if (candidate.getUniqueId().equals(owner)
                    || ((candidate instanceof WitherSkeleton || candidate instanceof org.bukkit.entity.Wither)
                            && skeletons.isOwnedBy(candidate, owner))) {
                event.setCancelled(true);
                summon.setTarget(null);
            }
        }
    }

    /** A skeleton that dies is dropped from tracking by the next sweep; nothing to do here. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSkeletonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof WitherSkeleton) {
            // Tracking removal is the sweep's job; this hook exists so a future change - drops,
            // effects - has an obvious home.
        }
    }
}
