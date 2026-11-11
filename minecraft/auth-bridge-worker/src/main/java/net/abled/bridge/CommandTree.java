package net.abled.bridge;

import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The commands the upstream offered this ghost, read off its {@code ClientboundCommandsPacket}.
 *
 * <p>The vanilla server builds that tree per player, and drops every node whose requirement the
 * sender fails - so a verb missing from it is a verb this account may not run here. That is the
 * difference between "the command did nothing" and "this account is not op on that server", which
 * is otherwise only visible as {@code command.unknown.command} in the reply text.
 */
final class CommandTree {

    /** Top-level literals the upstream sent to this account; empty when their packet lost. */
    private final Set<String> topLevel;

    private CommandTree(Set<String> topLevel) {
        this.topLevel = topLevel;
    }

    static CommandTree of(ClientboundCommandsPacket packet) {
        Set<String> names = new LinkedHashSet<>();
        CommandNode[] nodes = packet.getNodes();
        if (nodes != null && nodes.length > 0) {
            int root = packet.getFirstNodeIndex();
            if (root < 0 || root >= nodes.length) {
                root = 0;
            }
            int[] children = nodes[root] == null ? null : nodes[root].getChildIndices();
            if (children != null) {
                for (int child : children) {
                    if (child < 0 || child >= nodes.length || nodes[child] == null) {
                        continue;
                    }
                    String name = nodes[child].getName();
                    if (name != null && !name.isBlank()) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return new CommandTree(names);
    }

    boolean isEmpty() {
        return topLevel.isEmpty();
    }

    int size() {
        return topLevel.size();
    }

    /** Whether a bare verb (no slash, no arguments) is offered to this account. */
    boolean offers(String verb) {
        return verb != null && topLevel.contains(verb.toLowerCase(Locale.ROOT));
    }

    /**
     * Verbs the steps use that this tree does not contain, in first-seen order and without
     * duplicates. Empty when the tree never arrived, because then nothing can be concluded.
     */
    List<String> missingVerbs(List<String> steps) {
        List<String> missing = new ArrayList<>();
        if (topLevel.isEmpty()) {
            return missing;
        }
        for (String step : steps) {
            String verb = verbOf(step);
            if (!verb.isEmpty() && !offers(verb) && !missing.contains(verb)) {
                missing.add(verb);
            }
        }
        return missing;
    }

    /** The bare verb of a command line, without its leading slash or its arguments. */
    static String verbOf(String step) {
        if (step == null) {
            return "";
        }
        String line = step.strip();
        while (line.startsWith("/")) {
            line = line.substring(1);
        }
        int space = line.indexOf(' ');
        return (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
    }
}
