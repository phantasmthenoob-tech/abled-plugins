package net.abled.medieval.core.storage;

import java.util.List;

/**
 * The schema migrations shipped with this build, oldest first.
 *
 * <p>New migrations are appended here with the next zero-padded version and a matching resource
 * under {@code db/migration/}. Tables for systems that are not implemented yet are deliberately
 * absent: {@code wars}, {@code sieges} and {@code structures} arrive with their own migrations
 * when those subsystems land, so the schema never advertises data the plugin cannot maintain.
 */
public final class Migrations {

    private static final List<Migration> ALL = List.of(
            Migration.fromResource("001", "db/migration/001_init.sql"));

    private Migrations() {
    }

    public static List<Migration> all() {
        return ALL;
    }
}
