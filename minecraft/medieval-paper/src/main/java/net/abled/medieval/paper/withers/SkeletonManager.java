package net.abled.medieval.paper.withers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every summoned wither skeleton: who it belongs to, what it is attacking, and when to stop.
 *
 * <h2>The targeting rule, in one line</h2>
 * The owner's last attack is the source of truth. {@link #assignTarget} is called from the damage
 * listener when the owner hits something; every living skeleton they own switches to that exact
 * entity - not the nearest hostile, not a search, the entity they hit. The skeletons keep that
 * target until it dies, until the owner hits something else, or until {@code /wither dismiss}.
 * Nothing in the tick loop picks targets on its own.
 *
 * <h2>Owner protection</h2>
 * Two layers: the damage listener refuses any hit where the damager is a skeleton and the victim
 * is its owner or a fellow owned skeleton, and this manager clears any target that resolves to
 * the owner on the periodic sweep. Both read the ownership stamp from the skeleton's
 * PersistentDataContainer, so a skeleton is only "owned" if this plugin marked it.
 *
 * <h2>Tracking discipline</h2>
 * UUID keyed maps only; entity references are resolved through {@link Bukkit#getEntity} when
 * needed and never cached. The sweep runs a few times a second, not every tick, and iterates
 * this plugin's own maps - it never scans world entity lists.
 */
public final class SkeletonManager {

    /** PDC tag marking a skeleton as summoned by this plugin. */
    private final NamespacedKey keySummoned;
    /** PDC tag carrying the summoner's UUID. */
    private final NamespacedKey keySummoner;

    private final Plugin plugin;
    private final WitherSettings settings;

    /** Summoned skeletons per owner. */
    private final Map<UUID, Set<UUID>> ownedSkeletons = new ConcurrentHashMap<>();
    /** Summoned wither bosses per owner. */
    private final Map<UUID, Set<UUID>> ownedWithers = new ConcurrentHashMap<>();
    /** The owner's current target, from their last attack. */
    private final Map<UUID, UUID> currentTargets = new ConcurrentHashMap<>();

    private org.bukkit.scheduler.BukkitTask sweepTask;

    public SkeletonManager(Plugin plugin, WitherSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.keySummoned = new NamespacedKey(plugin, "summoned_wither_skeleton");
        this.keySummoner = new NamespacedKey(plugin, "summoner_uuid");
    }

    /** Starts the periodic sweep that applies targets and clears dead tracking. */
    public void start() {
        if (sweepTask != null) {
            return;
        }
        // Five times a second is plenty: targets change on the owner's attack, not continuously.
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 4L, 4L);
    }

    public void shutdown() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        ownedSkeletons.clear();
        currentTargets.clear();
    }

    /**
     * Spawns up to {@code amount} wither skeletons around the player, all owned by them.
     *
     * <p>Each one is made permanently neutral: its follows-limit is set to the hard ceiling so
     * vanilla target selection cannot acquire anything on its own - it attacks only when this
     * manager hands it the owner's target, and stands down when that target dies.
     *
     * @return how many were actually spawned (the per-player cap may cut the request short)
     */
    public int summon(Player player, int amount) {
        int owned = ownedSkeletons.computeIfAbsent(player.getUniqueId(),
                key -> ConcurrentHashMap.newKeySet()).size();
        int allowed = Math.min(amount, settings.maxSkeletonsPerPlayer() - owned);
        int spawned = 0;
        for (int index = 0; index < allowed; index++) {
            // A ring around the player, so five skeletons do not all land on the same block.
            double angle = (Math.PI * 2 * (owned + index)) / Math.max(settings.maxSkeletonsPerPlayer(), 1);
            Location spot = player.getLocation().clone()
                    .add(Math.cos(angle) * 2.0, 0, Math.sin(angle) * 2.0);
            if (!(player.getWorld().spawnEntity(spot, EntityType.WITHER_SKELETON)
                    instanceof WitherSkeleton skeleton)) {
                continue;
            }
            stamp(skeleton, player.getUniqueId());
            ownedSkeletons.get(player.getUniqueId()).add(skeleton.getUniqueId());
            spawned++;
        }
        return spawned;
    }

    /**
     * Spawns up to {@code amount} wither bosses around the player, all owned by them.
     *
     * <p>A commanded wither is the same deal as a commanded skeleton: owned, neutral, and it
     * attacks only what its owner strikes. One caveat the API forces: the vanilla wither builds
     * itself from player-placed soul sand/soil, and a spawned one does not carry an owner, so
     * withers that shoot at things do it through this manager's target assignments only - the
     * entity's own pickup logic is left dormant because nothing charged it.
     */
    public int summonWithers(Player player, int amount) {
        int owned = ownedWithers.computeIfAbsent(player.getUniqueId(),
                key -> ConcurrentHashMap.newKeySet()).size();
        int allowed = Math.min(amount, settings.maxWithersPerPlayer() - owned);
        int spawned = 0;
        for (int index = 0; index < allowed; index++) {
            double angle = (Math.PI * 2 * (owned + index)) / Math.max(settings.maxWithersPerPlayer(), 1);
            Location spot = player.getLocation().clone()
                    .add(Math.cos(angle) * 4.0, 1.0, Math.sin(angle) * 4.0);
            if (!(player.getWorld().spawnEntity(spot, EntityType.WITHER)
                    instanceof Wither wither)) {
                continue;
            }
            stamp(wither, player.getUniqueId());
            ownedWithers.get(player.getUniqueId()).add(wither.getUniqueId());
            spawned++;
        }
        return spawned;
    }

    /** Marks an entity as owned; shared by both summon paths. */
    private void stamp(Mob entity, UUID ownerId) {
        entity.getPersistentDataContainer().set(keySummoned, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(keySummoner, PersistentDataType.STRING,
                ownerId.toString());
        entity.setRemoveWhenFarAway(false);
        // Neutrality is enforced by the target-event allowlist in WitherListener, not by zeroing
        // follow range: a zeroed range stops vanilla from picking targets but also stops the
        // melee goal from pathing to an assigned one - half the skeletons just wandered. Full
        // range keeps combat behaviour working; the allowlist refuses every uncommanded choice.
    }

    /** Removes every summoned creature (skeletons and withers) the player owns. */
    public int dismiss(UUID ownerId) {
        int removed = removeTracked(ownedSkeletons.remove(ownerId));
        removed += removeTracked(ownedWithers.remove(ownerId));
        currentTargets.remove(ownerId);
        return removed;
    }

    /** Removes every summoned creature on the server, for the admin command. */
    public int dismissAll() {
        int removed = 0;
        for (UUID ownerId : List.copyOf(ownedSkeletons.keySet())) {
            removed += dismiss(ownerId);
        }
        for (UUID ownerId : List.copyOf(ownedWithers.keySet())) {
            removed += dismiss(ownerId);
        }
        return removed;
    }

    private int removeTracked(Set<UUID> ids) {
        int removed = 0;
        if (ids != null) {
            for (UUID id : ids) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** How many skeletons the player currently owns. */
    public int count(UUID ownerId) {
        Set<UUID> owned = ownedSkeletons.get(ownerId);
        return owned == null ? 0 : owned.size();
    }

    /** How many wither bosses the player currently owns. */
    public int countWithers(UUID ownerId) {
        Set<UUID> owned = ownedWithers.get(ownerId);
        return owned == null ? 0 : owned.size();
    }

    /** The UUID of the summoner of a stamped summon, read from its PDC; null when not one of ours. */
    public UUID ownerOf(Entity entity) {
        if (!(entity instanceof WitherSkeleton) && !(entity instanceof Wither)) {
            return null;
        }
        String stamped = entity.getPersistentDataContainer()
                .get(keySummoner, PersistentDataType.STRING);
        if (stamped == null) {
            return null;
        }
        try {
            return UUID.fromString(stamped);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Whether a skeleton belongs to the given owner, by PDC stamp. */
    public boolean isOwnedBy(Entity skeleton, UUID ownerId) {
        return ownerId.equals(ownerOf(skeleton));
    }

    /**
     * The owner just attacked a living entity: every skeleton they own now targets it.
     *
     * @return true when at least one skeleton took the target
     */
    public boolean assignTarget(UUID ownerId, LivingEntity victim) {
        if (ownerId.equals(victim.getUniqueId())) {
            return false;
        }
        currentTargets.put(ownerId, victim.getUniqueId());
        Set<UUID> owned = ownedSkeletons.get(ownerId);
        boolean any = false;
        if (owned != null) {
            for (UUID id : owned) {
                if (Bukkit.getEntity(id) instanceof Mob skeleton && skeleton.isValid()) {
                    skeleton.setTarget(victim);
                    any = true;
                }
            }
        }
        return any;
    }

    /** The owner's current target UUID, or null. */
    public UUID currentTarget(UUID ownerId) {
        return currentTargets.get(ownerId);
    }

    /** Clears the owner's current target without dismissing the skeletons. */
    public void clearTarget(UUID ownerId) {
        currentTargets.remove(ownerId);
    }

    /** Whether a skeleton's current target should be refused because it is the owner. */
    public boolean wouldTargetOwner(Mob skeleton, LivingEntity candidate) {
        UUID owner = ownerOf(skeleton);
        return owner != null && owner.equals(candidate.getUniqueId());
    }

    /**
     * The periodic sweep: push the current target to every owned skeleton, clear targets that
     * died, and forget skeletons that no longer exist. Cheap - it walks this plugin's own maps
     * and resolves a handful of entities per owner.
     */
    private void sweep() {
        Iterator<Map.Entry<UUID, Set<UUID>>> owners = ownedSkeletons.entrySet().iterator();
        while (owners.hasNext()) {
            Map.Entry<UUID, Set<UUID>> entry = owners.next();
            UUID ownerId = entry.getKey();
            Set<UUID> skeletons = entry.getValue();

            Iterator<UUID> ids = skeletons.iterator();
            while (ids.hasNext()) {
                Entity entity = Bukkit.getEntity(ids.next());
                if (!(entity instanceof WitherSkeleton skeleton) || !skeleton.isValid()
                        || skeleton.isDead()) {
                    ids.remove();
                }
            }
            if (skeletons.isEmpty()) {
                owners.remove();
                continue;
            }

            applyTarget(ownerId, skeletons);
        }

        // The wither bosses run the same loop over their own map.
        Iterator<Map.Entry<UUID, Set<UUID>>> witherOwners = ownedWithers.entrySet().iterator();
        while (witherOwners.hasNext()) {
            Map.Entry<UUID, Set<UUID>> entry = witherOwners.next();
            UUID ownerId = entry.getKey();
            Set<UUID> withers = entry.getValue();

            Iterator<UUID> ids = withers.iterator();
            while (ids.hasNext()) {
                Entity entity = Bukkit.getEntity(ids.next());
                if (!(entity instanceof Wither wither) || !wither.isValid() || wither.isDead()) {
                    ids.remove();
                }
            }
            if (withers.isEmpty()) {
                witherOwners.remove();
                continue;
            }
            applyTarget(ownerId, withers);
        }
    }

    /**
     * Pushes the owner's current target onto every tracked entity, standing them down when the
     * target is gone. Shared by the skeleton and wither loops.
     */
    private void applyTarget(UUID ownerId, Set<UUID> ids) {
        UUID targetId = currentTargets.get(ownerId);
        if (targetId == null) {
            return;
        }
        if (!(Bukkit.getEntity(targetId) instanceof LivingEntity target)
                || !target.isValid() || target.isDead()) {
            // The target is gone: the summons stand down rather than picking their own.
            currentTargets.remove(ownerId);
            for (UUID id : ids) {
                if (Bukkit.getEntity(id) instanceof Mob summon) {
                    summon.setTarget(null);
                }
            }
            return;
        }
        if (settings.targetPersistence()) {
            for (UUID id : ids) {
                if (Bukkit.getEntity(id) instanceof Mob summon && summon.isValid()) {
                    // Reasserting every sweep keeps vanilla AI from wandering to a nearer
                    // enemy: the owner's command is the source of truth.
                    summon.setTarget(target);
                }
            }
        }
    }

    /** The key marking a skeleton as summoned, for listeners that identify entities directly. */
    public NamespacedKey summonedKey() {
        return keySummoned;
    }

    public NamespacedKey summonerKey() {
        return keySummoner;
    }

    /** Snapshot of the tracked owners, for tests and status output. */
    public Set<UUID> trackedOwners() {
        return Set.copyOf(ownedSkeletons.keySet());
    }

    /** Living entities the manager is currently tracking for one owner (resolved). */
    public List<WitherSkeleton> skeletonsOf(UUID ownerId) {
        List<WitherSkeleton> result = new ArrayList<>();
        Set<UUID> owned = ownedSkeletons.get(ownerId);
        if (owned != null) {
            for (UUID id : owned) {
                if (Bukkit.getEntity(id) instanceof WitherSkeleton skeleton && skeleton.isValid()) {
                    result.add(skeleton);
                }
            }
        }
        return result;
    }
}
