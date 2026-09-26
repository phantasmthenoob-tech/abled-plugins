package net.abled.medieval.paper.catalogue;

import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.catalogue.Amounts;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a player a question in chat, and hands the answer to the GUI that asked it.
 *
 * <h2>Why chat, and not a sign or an anvil</h2>
 * Bedrock players arrive through Geyser, which does not carry the sign editor or anvil renaming
 * across. Typing into chat is the one text input every client has, so every prompt here is a chat
 * message and the player's next line is captured before it broadcasts.
 *
 * <h2>Three prompts, one capture</h2>
 * The catalogue search, a custom take amount, and an enchantment level all share this path. What
 * differs is the {@link Request} carried with the prompt: each kind holds its own context (the
 * clicked item, the enchantment being set) and answers {@link Request#accept} with what to do on
 * the tick thread. The capture, expiry and cancel rules stay written once.
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

    /** What to do with one chat answer, decided by the prompt kind. */
    private sealed interface Request {

        /** Message key naming this prompt, for expiry and cancel replies. */
        String kind();

        /**
         * Runs the answer on the tick thread.
         *
         * @return true when the answer was used; false to report it as unusable instead
         */
        boolean accept(Player player, String text, CatalogueSearch search);
    }

    /** A catalogue item search: the answer is what to look for. */
    private record SearchRequest(CatalogueMenu menu) implements Request {

        @Override
        public String kind() {
            return "search";
        }

        @Override
        public boolean accept(Player player, String text, CatalogueSearch search) {
            if (!menu.search(text)) {
                // Nothing to search for after normalisation (punctuation only, say): the menu is
                // reopened unchanged rather than showing an empty result for a query nobody made.
                menu.open(player, true);
                search.renderer.send(player, "catalogue-search-blank", true);
                return false;
            }

            menu.open(player, false);
            search.renderer.send(player, "catalogue-search-found", Map.of(
                    "query", menu.query(),
                    "count", Integer.toString(menu.resultCount())), true);
            return true;
        }
    }

    /** A take amount for one item: the answer is how many to give, right now. */
    private record AmountRequest(CatalogueMenu menu, Material material) implements Request {

        @Override
        public String kind() {
            return "amount";
        }

        @Override
        public boolean accept(Player player, String text, CatalogueSearch search) {
            return Amounts.parse(text, material.getMaxStackSize()).map(amount -> {
                search.handout.give(player, new ItemStack(material, amount));
                search.renderer.send(player, "catalogue-amount-given", Map.of(
                        "amount", Integer.toString(amount),
                        "item", Names.of(material)), true);
                return true;
            }).orElseGet(() -> {
                search.renderer.send(player, "catalogue-amount-bad", true);
                return false;
            });
        }
    }

    /** An enchantment level for the draft being built: the answer is how strong to make it. */
    private record LevelRequest(
            EnchantPicker picker, Enchantment enchantment, EnchantDraft draftAtPrompt) implements Request {

        @Override
        public String kind() {
            return "level";
        }

        @Override
        public boolean accept(Player player, String text, CatalogueSearch search) {
        java.util.Optional<Integer> level = Levels.parse(text, enchantment.getMaxLevel());
        if (level.isEmpty()) {
            search.renderer.send(player, "enchant-level-bad", Map.of(
                    "enchantment", Names.prettify(enchantment.getKey().getKey()),
                    "max", Integer.toString(enchantment.getMaxLevel())), true);
            return false;
        }            picker.apply(enchantment, level.get());
            search.renderer.send(player, "enchant-level-set", Map.of(
                    "enchantment", Names.prettify(enchantment.getKey().getKey()),
                    "level", EnchantDraft.roman(level.get())), true);
            return true;
        }
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final MedievalScheduler scheduler;
    private final MessageRenderer renderer;
    private final CatalogueHandout handout;

    public CatalogueSearch(MedievalScheduler scheduler, MessageRenderer renderer, CatalogueHandout handout) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.handout = Objects.requireNonNull(handout, "handout");
    }

    /**
     * Closes the menu and asks the player what to search for. Must be called on the tick thread,
     * because it closes an inventory.
     */
    public void prompt(Player player, CatalogueMenu menu) {
        ask(player, new SearchRequest(menu), "catalogue-search-prompt", Map.of(
                "timeout", TimeFormat.humanize(TIMEOUT),
                "cancel", CANCEL));
    }

    /**
     * Closes the menu and asks how many of the clicked item to give. The answer is handed out
     * immediately rather than only remembered, because that is what the click promised: the menu
     * shows which item was clicked and it would be odd to answer and then have to click again.
     */
    public void promptAmount(Player player, CatalogueMenu menu, Material material) {
        ask(player, new AmountRequest(menu, material), "catalogue-amount-prompt", Map.of(
                "item", Names.of(material),
                "stack", Integer.toString(Math.max(1, material.getMaxStackSize())),
                "timeout", TimeFormat.humanize(TIMEOUT),
                "cancel", CANCEL));
    }

    /**
     * Closes the picker and asks what level to give the enchantment. The draft travels with the
     * request so an answer that arrives after more clicks is still applied to a coherent item.
     */
    public void promptLevel(Player player, EnchantPicker picker, Enchantment enchantment) {
        EnchantDraft draft = picker.draft();
        ask(player, new LevelRequest(picker, enchantment, draft), "enchant-level-prompt", Map.of(
                "enchantment", Names.prettify(enchantment.getKey().getKey()),
                "item", Names.of(draft.material()),
                "max", Integer.toString(enchantment.getMaxLevel()),
                "timeout", TimeFormat.humanize(TIMEOUT),
                "cancel", CANCEL));
    }

    private void ask(Player player, Request request, String promptKey, Map<String, String> placeholders) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");

        // Abandoned prompts are dropped here rather than being left for the next chat line: the map
        // is tiny, and a stale entry could otherwise answer a message minutes later.
        Instant now = Instant.now();
        pending.values().removeIf(waiting -> waiting.expired(now));
        pending.put(player.getUniqueId(), new Pending(request, now.plus(TIMEOUT)));

        player.closeInventory();
        renderer.send(player, promptKey, placeholders, true);
    }

    /**
     * Offers one chat line to a waiting prompt.
     *
     * <p>Called from the asynchronous chat thread.
     *
     * @return true when the message was taken as an answer, and must therefore not be broadcast
     */
    public boolean consume(UUID playerId, String message) {
        Objects.requireNonNull(playerId, "playerId");

        Pending waiting = pending.remove(playerId);
        if (waiting == null) {
            return false;
        }
        if (waiting.expired(Instant.now())) {
            announce(playerId, "catalogue-search-expired");
            return false;
        }

        String text = message == null ? "" : message.trim();
        if (text.isEmpty() || CANCEL.equalsIgnoreCase(text)) {
            scheduler.runSync(() -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    reopen(waiting.request(), player);
                    renderer.send(player, "catalogue-search-cancelled", true);
                }
            });
            return true;
        }

        scheduler.runSync(() -> answer(playerId, waiting.request(), text));
        return true;
    }

    /** Runs one answer on the tick thread, reporting the ones no rule could use. */
    private void answer(UUID playerId, Request request, String text) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        if (!request.accept(player, text, this)) {
            renderer.send(player, "catalogue-answer-rejected", Map.of("kind", request.kind()), true);
        }
    }

    /** Puts the GUI the prompt came from back on screen, without repeating its click hint. */
    private void reopen(Request request, Player player) {
        switch (request) {
            case SearchRequest(CatalogueMenu menu) -> menu.open(player, false);
            case AmountRequest(CatalogueMenu menu, Material material) -> menu.open(player, false);
            case LevelRequest(EnchantPicker picker, Enchantment enchantment, EnchantDraft draft) ->
                    picker.open(player, draft, null);
        }
    }

    private void announce(UUID playerId, String key) {
        scheduler.runSync(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                renderer.send(player, key, true);
            }
        });
    }

    private record Pending(Request request, Instant deadline) {

        boolean expired(Instant now) {
            return !now.isBefore(deadline);
        }
    }
}
