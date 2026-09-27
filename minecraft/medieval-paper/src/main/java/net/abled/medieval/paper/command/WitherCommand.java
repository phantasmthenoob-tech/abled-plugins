package net.abled.medieval.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.abled.medieval.core.admin.OwnerGate;
import net.abled.medieval.paper.message.MessageRenderer;
import net.abled.medieval.paper.withers.SkeletonManager;
import net.abled.medieval.paper.withers.WitherMountManager;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@code /wither} - ride a wither skull, summon owned wither skeletons.
 *
 * <h2>Who may use it</h2>
 * Two gates must both pass: the sender is the configured owner ({@code admin.secret-owner}, the
 * same live supplier every hidden tool uses) <em>and</em> they are holding a sword named
 * exactly "Wither's Bane". The sword gate is a key, not a decoration: without it in hand the
 * branch is invisible, the commands answer nothing, and copy-pasting the spelling gains nothing
 * because the check runs on execution too.
 */
public final class WitherCommand {

    /** The exact display name a held sword must carry; case-sensitive, no colour codes. */
    public static final String SWORD_NAME = "Wither's Bane";

    public static final String LABEL = "wither";

    private final WitherMountManager mounts;
    private final SkeletonManager skeletons;
    private final MessageRenderer renderer;
    private final Supplier<OwnerGate> gate;

    public WitherCommand(WitherMountManager mounts, SkeletonManager skeletons,
                         MessageRenderer renderer, Supplier<OwnerGate> gate) {
        this.mounts = Objects.requireNonNull(mounts, "mounts");
        this.skeletons = Objects.requireNonNull(skeletons, "skeletons");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** The {@code wither} branch, registered as its own top-level command. */
    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(LABEL)
                // Only the owner sees the branch; the sword gate runs at execution, where a
                // failure can explain itself - a hidden command that says nothing when the sword
                // name is one character off is undiagnosable in game.
                .requires(source -> senderIsOwner(source.getSender()))
                .executes(context -> usage(context.getSource()))
                .then(Commands.literal("ride")
                        .executes(context -> ride(context.getSource(), null))
                        .then(Commands.literal("normal")
                                .executes(context -> ride(context.getSource(), false)))
                        .then(Commands.literal("charged")
                                .executes(context -> ride(context.getSource(), true))))
                .then(Commands.literal("mount")
                        .then(Commands.literal("normal")
                                .executes(context -> switchType(context.getSource(), false)))
                        .then(Commands.literal("charged")
                                .executes(context -> switchType(context.getSource(), true)))
                        .then(Commands.literal("toggle")
                                .executes(context -> toggleType(context.getSource()))))
                .then(Commands.literal("summon")
                        .executes(context -> summon(context.getSource(), 1))
                        .then(Commands.literal("skeleton")
                                .executes(context -> summon(context.getSource(), 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> summon(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "amount")))))
                        .then(Commands.literal("wither")
                                .executes(context -> summonWither(context.getSource(), 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 8))
                                        .executes(context -> summonWither(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("dismiss")
                        .executes(context -> dismiss(context.getSource())))
                .then(Commands.literal("dismissall")
                        .executes(context -> dismissAll(context.getSource())))
                .then(Commands.literal("target")
                        .executes(context -> targetStatus(context.getSource())))
                .build();
    }

    /** The visibility gate: the configured owner, nothing else. */
    private boolean senderIsOwner(CommandSender sender) {
        if (sender instanceof Player player) {
            return gate.get().allows(player.getUniqueId(), player.getName());
        }
        return false;
    }

    /** Whether the player holds the key sword; exposed for the saddlebag interaction too. */
    public boolean hasKeySword(Player player) {
        return holdingWithersBane(player);
    }

    /** The execution gate: owner (re-checked) AND holding a sword named Wither's Bane. */
    private boolean allowed(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return false;
        }
        if (!gate.get().allows(player.getUniqueId(), player.getName())) {
            return false;
        }
        return holdingWithersBane(player);
    }

    /**
     * Guards a subcommand: refuses with an explicit message when the sword is missing or
     * wrongly named, and reports exactly what the held item's name rendered as, so a near-miss
     * name (a space, a different apostrophe, leftover formatting) is visible instead of silent.
     */
    private int checkSword(CommandSourceStack source) {
        Player player = (Player) source.getSender();
        if (holdingWithersBane(player)) {
            return -1;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        String heldName = held.hasItemMeta() && held.getItemMeta().displayName() != null
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(held.getItemMeta().displayName())
                : held.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        renderer.send(player, "wither-no-sword", Map.of(
                "expected", SWORD_NAME,
                "held", heldName.isEmpty() ? "(nothing)" : heldName), true);
        return Command.SINGLE_SUCCESS;
    }

    /** Whether the player's main hand holds a sword whose display name is exactly the key. */
    private boolean holdingWithersBane(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.NETHERITE_SWORD
                && held.getType() != Material.DIAMOND_SWORD
                && held.getType() != Material.IRON_SWORD
                && held.getType() != Material.GOLDEN_SWORD
                && held.getType() != Material.WOODEN_SWORD
                && held.getType() != Material.STONE_SWORD) {
            return false;
        }
        if (!held.hasItemMeta() || held.getItemMeta().displayName() == null) {
            return false;
        }
        // Compared on the rendered plain text, so colour codes cannot sneak a fake key through,
        // and an anvil-renamed sword with exactly this text works like the real one.
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(held.getItemMeta().displayName()).equals(SWORD_NAME);
    }

    private int usage(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        renderer.send(sender, "wither-usage", false);
        return Command.SINGLE_SUCCESS;
    }

    private int ride(CommandSourceStack source, Boolean charged) {
        if (checkSword(source) >= 0) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = (Player) source.getSender();
        boolean wanted = charged != null ? charged : false;
        if (!mounts.create(player, wanted)) {
            renderer.send(player, "wither-already-riding", true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int switchType(CommandSourceStack source, boolean charged) {
        if (checkSword(source) >= 0) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = (Player) source.getSender();
        if (mounts.mount(player.getUniqueId()).isEmpty()) {
            renderer.send(player, "wither-not-riding", true);
            return Command.SINGLE_SUCCESS;
        }
        if (mounts.setType(player.getUniqueId(), charged)) {
            renderer.send(player, charged ? "wither-type-charged" : "wither-type-normal", true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int toggleType(CommandSourceStack source) {
        if (checkSword(source) >= 0) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = (Player) source.getSender();
        var mount = mounts.mount(player.getUniqueId());
        if (mount.isEmpty()) {
            renderer.send(player, "wither-not-riding", true);
            return Command.SINGLE_SUCCESS;
        }
        boolean charged = !mount.get().charged();
        mounts.setType(player.getUniqueId(), charged);
        renderer.send(player, charged ? "wither-type-charged" : "wither-type-normal", true);
        return Command.SINGLE_SUCCESS;
    }

    private int summon(CommandSourceStack source, int amount) {
        if (checkSword(source) >= 0) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = (Player) source.getSender();
        int spawned = skeletons.summon(player, amount);
        if (spawned <= 0) {
            renderer.send(player, "wither-summon-capped", Map.of(
                    "count", String.valueOf(skeletons.count(player.getUniqueId())), "max",
                    String.valueOf(mounts.settings().maxSkeletonsPerPlayer())), true);
        } else {
            renderer.send(player, "wither-summoned", Map.of(
                    "count", String.valueOf(spawned),
                    "total", String.valueOf(skeletons.count(player.getUniqueId()))), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int summonWither(CommandSourceStack source, int amount) {
        if (checkSword(source) >= 0) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = (Player) source.getSender();
        int spawned = skeletons.summonWithers(player, amount);
        if (spawned <= 0) {
            renderer.send(player, "wither-summon-wither-capped", Map.of(
                    "count", String.valueOf(skeletons.countWithers(player.getUniqueId())), "max",
                    String.valueOf(mounts.settings().maxWithersPerPlayer())), true);
        } else {
            renderer.send(player, "wither-summon-withered", Map.of(
                    "count", String.valueOf(spawned),
                    "total", String.valueOf(skeletons.countWithers(player.getUniqueId()))), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int dismiss(CommandSourceStack source) {
        if (checkSword(source) >= 0) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = (Player) source.getSender();
        int removed = skeletons.dismiss(player.getUniqueId());
        renderer.send(player, "wither-dismissed", Map.of(
                "count", String.valueOf(removed)), true);
        return Command.SINGLE_SUCCESS;
    }

    private int dismissAll(CommandSourceStack source) {
        if (checkSword(source) >= 0) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = (Player) source.getSender();
        int removed = skeletons.dismissAll();
        renderer.send(player, "wither-dismissed-all", Map.of(
                "count", String.valueOf(removed)), true);
        return Command.SINGLE_SUCCESS;
    }

    private int targetStatus(CommandSourceStack source) {
        if (checkSword(source) >= 0) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = (Player) source.getSender();
        java.util.UUID targetId = skeletons.currentTarget(player.getUniqueId());
        if (targetId == null) {
            renderer.send(player, "wither-target-none", true);
            return Command.SINGLE_SUCCESS;
        }
        org.bukkit.entity.Entity entity = org.bukkit.Bukkit.getEntity(targetId);
        String name = entity == null ? "unknown" : entity.getName();
        renderer.send(player, "wither-target-current", Map.of("target", name), true);
        return Command.SINGLE_SUCCESS;
    }
}
