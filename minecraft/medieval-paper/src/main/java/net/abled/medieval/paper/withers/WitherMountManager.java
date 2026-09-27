package net.abled.medieval.paper.withers;

import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One rideable wither skull per owner, built as a two-layer mount.
 *
 * <h2>Why two entities</h2>
 * A {@link WitherSkull} is a projectile: the server steers it, it accelerates on its own, and it
 * explodes on the first thing it touches. Nothing about it accepts a rider's steering input. So
 * the player rides an invisible, marker armour stand - which Minecraft happily steers with WASD,
 * the same input path as a horse - and the visible skull follows the stand every tick. The
 * player's own {@link org.bukkit.Input} is read directly and turned into acceleration, so W flies
 * where they look, space climbs, shift dives, and momentum and drag make it feel like flying
 * rather than teleporting.
 *
 * <h2>What is ticked, and what is not</h2>
 * One shared repeating task iterates the {@link #mounts} map: validate the three entities, apply
 * steering to the stand's velocity, then put the skull on top of the stand. Nothing scans the
 * world's entities; every relationship is a UUID resolved through {@link Bukkit#getEntity}.
 *
 * <h2>Switching normal / charged</h2>
 * {@code WitherSkull.setCharged(boolean)} exists on the Paper 26.2 API, so the switch is a
 * property change on the same entity - no respawn, nothing the player could fall out of mid-air.
 * {@link #setType} flips it and keeps velocity, facing and the mount relationship untouched.
 */
public final class WitherMountManager {

    /** PDC tag on the control stand and the skull; marks both as this plugin's entities. */
    private final NamespacedKey keyMount;
    /** PDC tag on the control stand and the skull: the owner's UUID. */
    private final NamespacedKey keyOwner;
    /** PDC tag on the skull: "normal" or "charged". */
    private final NamespacedKey keyType;

    private final Plugin plugin;
    private final WitherSettings settings;
    private final MessageRenderer renderer;
    private final java.util.function.Consumer<String> warnings;

    /**
     * Active mounts keyed by the owner's UUID. Concurrent because a dismount event and the tick
     * task can both touch it within one tick; every other access is on the main thread anyway.
     */
    private final Map<UUID, RideableWither> mounts = new ConcurrentHashMap<>();

    /** The tick task's handle; cancelled on disable so no task outlives the plugin. */
    private org.bukkit.scheduler.BukkitTask tickTask;

    public WitherMountManager(Plugin plugin, WitherSettings settings, MessageRenderer renderer,
                              java.util.function.Consumer<String> warnings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.keyMount = new NamespacedKey(plugin, "rideable_wither_skull");
        this.keyOwner = new NamespacedKey(plugin, "rideable_wither_owner");
        this.keyType = new NamespacedKey(plugin, "rideable_wither_type");
    }

    /** Starts the shared per-tick movement task. Call once from onEnable. */
    public void start() {
        if (tickTask != null) {
            return;
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Stops the tick task and destroys every mount. Call from onDisable. */
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (UUID owner : List.copyOf(mounts.keySet())) {
            destroy(owner, false);
        }
    }

    /**
     * Mounts the player on a new skull, or reports that they already have one.
     *
     * @return true when a mount was created, false when the player is already riding
     */
    public boolean create(Player player, boolean charged) {
        if (mounts.containsKey(player.getUniqueId())) {
            return false;
        }

        // The control vehicle: a marker armour stand. Marker stands have no hitbox to click, no
        // gravity, no AI and nothing to interact with, and a rider's movement input steers one
        // exactly as it steers a pig or a horse. Invisible so only the skull shows.
        ArmorStand vehicle = (ArmorStand) player.getWorld().spawnEntity(
                player.getLocation(), EntityType.ARMOR_STAND);
        vehicle.setVisible(false);
        vehicle.setMarker(true);
        vehicle.setGravity(false);
        vehicle.setInvulnerable(true);
        vehicle.setSilent(true);
        vehicle.setAI(false);
        vehicle.setCollidable(false);
        vehicle.setPersistent(false);
        vehicle.getPersistentDataContainer().set(keyMount, PersistentDataType.BYTE, (byte) 1);
        vehicle.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING,
                player.getUniqueId().toString());

        if (!vehicle.addPassenger(player)) {
            vehicle.remove();
            return false;
        }

        WitherSkull skull = spawnSkull(vehicle.getLocation(), player.getUniqueId(), charged);

        RideableWither mount = new RideableWither(player.getUniqueId(), vehicle.getUniqueId(),
                skull.getUniqueId(), charged, System.currentTimeMillis(), new Vector());
        mounts.put(player.getUniqueId(), mount);

        renderer.send(player, charged ? "wither-ride-charged" : "wither-ride-normal", true);
        if (settings.mountParticles()) {
            player.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE,
                    skull.getLocation(), 12, 0.3, 0.3, 0.3, 0.01);
        }
        return true;
    }

    /** The player's active mount, or empty. */
    public Optional<RideableWither> mount(UUID ownerId) {
        return Optional.ofNullable(mounts.get(ownerId));
    }

    /** Whether this entity UUID is a control vehicle or skull of one of the mounts. */
    public boolean isMountEntity(UUID entityUuid) {
        for (RideableWither mount : mounts.values()) {
            if (mount.controlVehicle().equals(entityUuid) || mount.visibleSkull().equals(entityUuid)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this entity is a plugin control vehicle, checked through its PDC stamp. */
    public boolean isControlVehicle(Entity entity) {
        return entity instanceof ArmorStand
                && entity.getPersistentDataContainer().has(keyMount, PersistentDataType.BYTE);
    }

    /**
     * Switches the mount's skull type without dismounting.
     *
     * <p>The charged state is a property of the skull entity, so the same skull stays in the air:
     * velocity, facing and the ride are untouched.
     *
     * @return true when the type changed, false when there was no mount
     */
    public boolean setType(UUID ownerId, boolean charged) {
        RideableWither mount = mounts.get(ownerId);
        if (mount == null || mount.charged() == charged) {
            return false;
        }
        Entity skull = Bukkit.getEntity(mount.visibleSkull());
        if (skull instanceof WitherSkull witherSkull) {
            witherSkull.setCharged(charged);
            skull.getPersistentDataContainer().set(keyType, PersistentDataType.STRING,
                    charged ? "charged" : "normal");
        }
        mounts.put(ownerId, mount.withCharged(charged));
        return true;
    }

    /**
     * Removes a player's mount: the control vehicle, the skull, and all tracking.
     *
     * @param notify whether the player is still around to be told
     */
    public void destroy(UUID ownerId, boolean notify) {
        RideableWither mount = mounts.remove(ownerId);
        if (mount == null) {
            return;
        }
        Entity vehicle = Bukkit.getEntity(mount.controlVehicle());
        if (vehicle != null) {
            // Eject first so the player is not riding an entity that is about to vanish under
            // them; dismounting is what the vanilla code does when a vehicle is removed anyway.
            if (vehicle.isValid()) {
                vehicle.eject();
            }
            vehicle.remove();
        }
        Entity skull = Bukkit.getEntity(mount.visibleSkull());
        if (skull != null) {
            skull.remove();
        }
        Player player = Bukkit.getPlayer(ownerId);
        if (notify && player != null) {
            renderer.send(player, "wither-dismounted", true);
        }
    }

    /** Whether an explosion from a mount skull should be allowed at all. */
    public boolean skullShouldExplode(boolean charged) {
        return settings.collisionExplosion();
    }

    /** The configured explosion yield for a mount skull of the given type. */
    public double explosionPower(boolean charged) {
        return settings.explosionPower(charged);
    }

    public WitherSettings settings() {
        return settings;
    }

    /**
     * One tick of the movement loop: steer the stands, carry the skulls, drop dead mounts.
     */
    private void tick() {
        Iterator<Map.Entry<UUID, RideableWither>> iterator = mounts.entrySet().iterator();
        while (iterator.hasNext()) {
            RideableWither mount = iterator.next().getValue();

            Entity vehicleEntity = Bukkit.getEntity(mount.controlVehicle());
            Entity skullEntity = Bukkit.getEntity(mount.visibleSkull());
            Player rider = Bukkit.getPlayer(mount.owner());

            // Any broken link - the vehicle removed by another plugin, the skull exploded, the
            // rider gone - tears the whole mount down rather than leaving half of it behind.
            if (rider == null || !rider.isOnline()
                    || !(vehicleEntity instanceof ArmorStand vehicle) || !vehicle.isValid()
                    || !(skullEntity instanceof WitherSkull skull) || !skull.isValid()
                    || !vehicle.getPassengers().contains(rider)) {
                destroy(mount.owner(), false);
                iterator.remove();
                continue;
            }

            // Death also drops the ride; the death listener handles the message, the tick loop
            // only has to notice the vehicle no longer carries anyone.
            if (rider.isDead()) {
                destroy(mount.owner(), false);
                iterator.remove();
                continue;
            }

            steer(mount, rider, vehicle, skull);
        }
    }

    /**
     * Applies the rider's input as acceleration on the control stand, then glues the skull on top.
     */
    private void steer(RideableWither mount, Player rider, ArmorStand vehicle, WitherSkull skull) {
        org.bukkit.Input input = rider.getCurrentInput();

        double forward = 0.0;
        double strafe = 0.0;
        double vertical = 0.0;
        if (input != null) {
            if (input.isForward()) {
                forward += 1.0;
            }
            if (input.isBackward()) {
                forward -= 1.0;
            }
            if (input.isLeft()) {
                strafe += 1.0;
            }
            if (input.isRight()) {
                strafe -= 1.0;
            }
            if (input.isJump()) {
                vertical += 1.0;
            }
            if (input.isSneak()) {
                vertical -= 1.0;
            }
        }

        Location eye = rider.getEyeLocation();
        // Look direction, flattened to the horizontal plane for W/S so looking down does not dive.
        Vector look = eye.getDirection();
        Vector forwardDir = new Vector(look.getX(), 0, look.getZ());
        if (forwardDir.lengthSquared() < 1.0E-4) {
            forwardDir = new Vector(0, 0, 1);
        } else {
            forwardDir.normalize();
        }
        // Right-hand strafe vector: 90 degrees clockwise from forward on the horizontal plane.
        Vector rightDir = new Vector(-forwardDir.getZ(), 0, forwardDir.getX());

        double speed = settings.speed(mount.charged());
        Vector desired = forwardDir.multiply(forward * speed)
                .add(rightDir.multiply(strafe * speed));
        desired.setY(vertical * settings.verticalSpeed());

        // Accelerate toward the desired velocity, then apply drag. This is what makes the mount
        // feel like flying: the response ramps in over a few ticks, and letting go of the keys
        // glides to a stop instead of dead-stopping.
        Vector velocity = mount.velocity();
        double acceleration = settings.acceleration();
        velocity.add(desired.subtract(velocity).multiply(acceleration));
        velocity.multiply(settings.drag());
        mount.setVelocity(velocity);

        vehicle.setVelocity(velocity);

        // The skull rides exactly on the control stand. The stand is invisible, so the skull is
        // the thing the player sees beneath them - inside it, at the eye line of a rider.
        skull.setRotation(vehicle.getLocation().getYaw(), vehicle.getLocation().getPitch());
        skull.setVelocity(velocity);
        skull.teleport(vehicle.getLocation());
    }

    /**
     * Spawns one visible skull, stamped as plugin-owned, at the given spot.
     */
    private WitherSkull spawnSkull(Location location, UUID owner, boolean charged) {
        // Spawning a WitherSkull directly would launch it; spawning with no shooter and zero
        // velocity keeps it hanging where it is put, and the tick loop owns its motion from then
        // on. setPersistent(false) keeps a server crash from littering the world with skulls.
        WitherSkull skull = (WitherSkull) location.getWorld().spawnEntity(
                location, EntityType.WITHER_SKULL);
        skull.setCharged(charged);
        skull.setGravity(false);
        skull.setPersistent(false);
        skull.setSilent(true);
        skull.setYield((float) settings.explosionPower(charged));
        skull.getPersistentDataContainer().set(keyMount, PersistentDataType.BYTE, (byte) 1);
        skull.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING,
                owner.toString());
        skull.getPersistentDataContainer().set(keyType, PersistentDataType.STRING,
                charged ? "charged" : "normal");
        return skull;
    }

    /** Read-only namespaced keys for other listeners to identify mount entities with. */
    public NamespacedKey mountKey() {
        return keyMount;
    }

    public NamespacedKey ownerKey() {
        return keyOwner;
    }

    public NamespacedKey typeKey() {
        return keyType;
    }

    /** In-memory mount state; the entities themselves carry the durable PDC stamps. */
    public record RideableWither(UUID owner, UUID controlVehicle, UUID visibleSkull,
                                 boolean charged, long createdAt, Vector velocity) {

        public RideableWither {
            velocity = velocity.clone();
        }

        public RideableWither withCharged(boolean charged) {
            return new RideableWither(owner, controlVehicle, visibleSkull, charged, createdAt, velocity);
        }

        public void setVelocity(Vector velocity) {
            // Records are immutable; the tick loop holds the live one, so mutate through the map.
            // This setter exists to keep the call sites honest about what they are changing.
            this.velocity.copy(velocity);
        }
    }
}
