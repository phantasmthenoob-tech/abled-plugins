package net.abled.medieval.paper.storage;

import net.abled.medieval.core.deathban.SqlDeathbanStore;
import net.abled.medieval.core.kingdom.KingdomRepository;
import net.abled.medieval.core.player.PlayerRepository;
import net.abled.medieval.core.storage.ConnectionFactory;
import net.abled.medieval.core.storage.Database;
import net.abled.medieval.core.storage.DriverManagerConnectionFactory;
import net.abled.medieval.core.storage.JdbcStorageService;
import net.abled.medieval.core.storage.Migrations;
import net.abled.medieval.core.storage.StorageException;
import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.territory.ClaimRepository;
import net.abled.medieval.core.world.WorldStateRepository;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * The plugin's persistence: one SQLite file inside the plugin data folder, the repositories built
 * on top of it, and its lifecycle.
 *
 * <p>Everything database-product specific lives here - the JDBC URL, the driver class and the
 * session pragmas. The core supplies the SQL, the schema and the repositories and stays database
 * agnostic, which is the split that lets the same core run behind another platform or store.
 *
 * <p>The driver itself is not bundled: it is declared in {@code plugin.yml} under {@code libraries}
 * and downloaded by the server from Maven Central (see {@link #DRIVER}).
 */
public final class PaperStorage implements AutoCloseable {

    /** Database file name inside the plugin data folder. */
    public static final String FILE_NAME = "medieval.db";

    /** Supplied by the server through the plugin's {@code libraries} entry. */
    public static final String DRIVER = "org.sqlite.JDBC";

    /**
     * Applied to every new connection:
     * <ul>
     *   <li>{@code foreign_keys} - the schema's {@code ON DELETE CASCADE} rules only work when this
     *       is enabled, and SQLite defaults it off per connection;</li>
     *   <li>{@code journal_mode WAL} - readers no longer block the writer, which matters because
     *       logins read while a death writes;</li>
     *   <li>{@code synchronous NORMAL} - durable with WAL and far cheaper than {@code FULL} for the
     *       single-row writes this plugin performs;</li>
     *   <li>{@code busy_timeout} - waits for the file lock instead of failing instantly.</li>
     * </ul>
     */
    private static final List<String> SESSION_PRAGMAS = List.of(
            "PRAGMA foreign_keys = ON",
            "PRAGMA journal_mode = WAL",
            "PRAGMA synchronous = NORMAL",
            "PRAGMA busy_timeout = 5000");

    private final StorageService storage;
    private final PaperStorageLog log;
    private final PlayerRepository players;
    private final KingdomRepository kingdoms;
    private final ClaimRepository claims;
    private final WorldStateRepository worldState;
    private final SqlDeathbanStore deathbans;

    private PaperStorage(StorageService storage, PaperStorageLog log) {
        this.storage = storage;
        this.log = log;
        this.players = new PlayerRepository(storage.database(), log);
        this.kingdoms = new KingdomRepository(storage.database(), log);
        this.claims = new ClaimRepository(storage.database(), log);
        this.worldState = new WorldStateRepository(storage.database(), log);
        this.deathbans = new SqlDeathbanStore(storage.database(), log);
    }

    /**
     * Opens the database and brings its schema up to date.
     *
     * @throws StorageException when the folder cannot be created, the driver is missing or a
     *                          migration fails. The caller must then refuse to start: a game system
     *                          that silently loses kingdoms, claims and bans is worse than one that
     *                          does not start at all.
     */
    public static PaperStorage open(JavaPlugin plugin) {
        PaperStorageLog log = new PaperStorageLog(plugin.getLogger());

        File folder = plugin.getDataFolder();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new StorageException("could not create the plugin data folder: " + folder);
        }
        File file = new File(folder, FILE_NAME);

        ConnectionFactory connections = new DriverManagerConnectionFactory(
                "jdbc:sqlite:" + file.getAbsolutePath(), DRIVER, SESSION_PRAGMAS);

        JdbcStorageService storage = new JdbcStorageService(connections, Migrations.all(), log);
        storage.open();
        log.info("Schema at version " + String.join(", ", storage.appliedMigrations())
                + " in " + file.getAbsolutePath());

        return new PaperStorage(storage, log);
    }

    public Database database() {
        return storage.database();
    }

    public PlayerRepository players() {
        return players;
    }

    /** Used by the kingdom phase; created here so that phase starts from a wired repository. */
    public KingdomRepository kingdoms() {
        return kingdoms;
    }

    /** Used by the territory phase. */
    public ClaimRepository claims() {
        return claims;
    }

    /** Used by the dimension/event phases for runtime state that must survive a restart. */
    public WorldStateRepository worldState() {
        return worldState;
    }

    public SqlDeathbanStore deathbans() {
        return deathbans;
    }

    public List<String> appliedMigrations() {
        return storage.appliedMigrations();
    }

    /**
     * Closes the connection. Any statement already running finishes first, so a shutdown during a
     * write either commits it or rolls it back - it cannot leave a half-written transaction behind.
     */
    @Override
    public void close() {
        storage.close();
        log.info("Database connection closed");
    }
}
