package net.abled.medieval.core.message;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceTest {

    private final MessageService service = new MessageService(new MapMessageSource(Map.of(
            "prefix", "<gold>[Medieval] ",
            "no-permission", "<red>You do not have permission to do that.",
            "reload-success", "<green>Reloaded in {millis} ms.",
            "two.tokens", "{first} and {second}")));

    @Test
    void returnsTemplateWhenNoPlaceholdersAreUsed() {
        assertEquals("<red>You do not have permission to do that.", service.noPermission());
        assertEquals("<gold>[Medieval] ", service.prefix());
    }

    @Test
    void substitutesPlaceholders() {
        assertEquals("<green>Reloaded in 42 ms.", service.raw("reload-success", Map.of("millis", "42")));
        assertEquals("<green>Reloaded in {millis} ms.", service.raw("reload-success"));
    }

    @Test
    void substitutesEveryOccurrenceAndMultipleTokens() {
        assertEquals("a and b", service.raw("two.tokens", Map.of("first", "a", "second", "b")));
        assertEquals("x and x", service.raw("two.tokens", Map.of("first", "x", "second", "x")));
    }

    @Test
    void substitutesPlaceholdersOnEveryLineOfAMultiLineTemplate() {
        // Item lore is a single multi-line entry in messages.yml; the renderer splits it into lines
        // and relies on the substitution having reached every one of them, not just the first.
        MessageService lore = new MessageService(new MapMessageSource(Map.of(
                "hint", "<gray>line one takes {amount}\n<gray>line two takes {amount}")));

        String resolved = lore.raw("hint", Map.of("amount", "8"));

        assertEquals(2, resolved.lines().count());
        assertTrue(resolved.lines().allMatch(line -> line.contains("8")), resolved);
        assertFalse(resolved.contains("{amount}"), resolved);
    }

    @Test
    void reportsMissingKeysWithoutFailing() {
        String resolved = service.raw("does.not.exist", Map.of());

        assertTrue(resolved.contains("does.not.exist"), resolved);
    }

    @Test
    void reloadSwapsTheSource() {
        service.reload(new MapMessageSource(Map.of("prefix", "<blue>[New] ")));

        assertEquals("<blue>[New] ", service.prefix());
        assertTrue(service.raw("no-permission").contains("no-permission"));
    }
}
