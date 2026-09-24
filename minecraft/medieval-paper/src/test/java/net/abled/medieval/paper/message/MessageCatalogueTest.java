package net.abled.medieval.paper.message;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every message key the plugin asks for exists in the bundled {@code messages.yml}.
 *
 * <h2>Why this deserves a test</h2>
 * A missing key is not a crash, which is exactly what makes it easy to ship. {@code MessageService}
 * renders {@code Missing message: <key>} in its place, so the failure surfaces as a chest GUI whose
 * buttons are named with a red error instead of "Next page" or "Search" - which is how it was
 * noticed in game, not by any tooling. Nothing else in the build can catch it: the key is a plain
 * string in bytecode and the file it has to appear in is a resource.
 *
 * <p>The scan covers {@code src/main/java}. Every lower-case hyphenated string literal in the
 * platform module is a message key - it has no other use for that shape - so a key added to a menu
 * and forgotten in the file fails the build here rather than in front of a player.
 *
 * <p>The keys are read back from the classpath rather than from the source tree, so this also fails
 * if {@code messages.yml} stops being packaged into the plugin jar.
 */
class MessageCatalogueTest {

    /** A message key: lower-case, hyphen-separated, for example {@code catalogue-no-next}. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"([a-z][a-z0-9]*(?:-[a-z0-9]+)+)\"");

    /** A key definition: a name at the start of a line, followed by a colon. */
    private static final Pattern KEY_DEFINITION = Pattern.compile("(?m)^([a-z][a-z0-9]*(?:-[a-z0-9]+)*):");

    /** The packaged resource. Deliberately the classpath, not the file on disk. */
    private static final String BUNDLED_MESSAGES = "/messages.yml";

    /** Relative to the project directory, which is where Gradle runs the tests. */
    private static final Path SOURCES = Path.of("src", "main", "java");

    @Test
    void everyKeyThePluginAsksForIsDefined() throws IOException {
        Set<String> defined = definedKeys();
        Set<String> used = usedKeys();

        assertFalse(used.isEmpty(),
                "the source scan found no message keys at all, so it is not looking at the sources");

        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(defined);
        assertTrue(missing.isEmpty(), () -> messagesMissing(missing));
    }

    @Test
    void theKeysResolvedDirectlyByTheCoreAreDefined() throws IOException {
        // These are looked up by MessageService itself rather than through MessageRenderer, so the
        // scan above cannot see them - a single-word key has no hyphen for the pattern to match.
        Set<String> defined = definedKeys();

        assertTrue(defined.contains("prefix"), "prefix is prepended to every prefixed message");
        assertTrue(defined.contains("no-permission"), "sent when a command's permission is missing");
    }

    private static String messagesMissing(Set<String> missing) {
        return "messages.yml is missing " + missing.size() + " key(s) that the plugin asks for, so "
                + "they would render as 'Missing message: <key>' in game: " + String.join(", ", missing);
    }

    /** The keys the YAML file defines, read from the plugin's own classpath. */
    private static Set<String> definedKeys() throws IOException {
        try (InputStream stream = MessageCatalogueTest.class.getResourceAsStream(BUNDLED_MESSAGES)) {
            if (stream == null) {
                throw new IOException(BUNDLED_MESSAGES + " is not on the plugin's classpath, so the "
                        + "server would render every message as 'Missing message: <key>'");
            }

            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> keys = new LinkedHashSet<>();
            Matcher matcher = KEY_DEFINITION.matcher(yaml);
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
            return keys;
        }
    }

    /**
     * Every message key literal in the platform sources.
     *
     * <p>A multi-line YAML block scalar is read as prose here, which is harmless: its lines are
     * indented, so they never look like a key definition, and its placeholders are not keys.
     */
    private static Set<String> usedKeys() throws IOException {
        if (!Files.isDirectory(SOURCES)) {
            throw new IOException("expected the plugin sources at " + SOURCES.toAbsolutePath()
                    + "; run this test through Gradle, which runs it from the project directory");
        }

        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collect(read(path), keys));
        }
        return keys;
    }

    private static void collect(String source, Set<String> keys) {
        Matcher matcher = KEY_LITERAL.matcher(source);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
