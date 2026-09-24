package net.abled.medieval.core.message;

import java.util.Optional;

/** Provides raw message templates, keyed as in {@code messages.yml}. */
@FunctionalInterface
public interface MessageSource {

    Optional<String> template(String key);
}
