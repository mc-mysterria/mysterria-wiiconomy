package dev.ua.ikeepcalm.wiic.domain.agora.db;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns the single SQLite connection behind the Underground Market.
 *
 * <p>SQLite allows one writer at a time, so all access is funnelled through a
 * single-threaded executor ({@code WIIC-MarketDB}) instead of a pool. Never touch
 * the connection from any other thread. {@link #submit} returns a future completed
 * on the DB thread; use {@link #submitThenMain} when the continuation touches
 * Bukkit API — it re-schedules onto the main thread, mirroring the async-Vault →
 * main-thread hop in {@code PurchaseService}.
 *
 * <p>{@link #shutdown()} drains the executor before closing so in-flight escrow
 * writes always land on disk during onDisable.
 */
public class MarketDatabase {

    /**
     * Work unit executed on the DB thread with the shared connection.
     */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Marker for expected control-flow aborts thrown inside {@link SqlWork} (limit
     * hit, reservation lost, ...) — they roll the transaction back like any error
     * but are not logged as DB failures.
     */
    public abstract static class ControlFlow extends RuntimeException {
        protected ControlFlow(String message) {
            super(message, null, false, false);
        }
    }

    private final WIIC plugin;
    private final ExecutorService executor;
    private Connection connection;

    public MarketDatabase(WIIC plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WIIC-MarketDB");
            t.setDaemon(false);
            return t;
        });
    }

    /**
     * Opens the connection and applies migrations. Blocks the caller (call once from onEnable).
     */
    public void open() throws Exception {
        executor.submit(() -> {
            File file = new File(plugin.getDataFolder(), "market.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                // FULL, not NORMAL: the write-ahead journal fsyncs every entry, and every
                // flow prunes its journal entry the moment a commit returns. Under NORMAL a
                // power loss can roll back a commit the journal already treated as proof —
                // recovery would then read a lie and pay a ledger claim out twice.
                st.execute("PRAGMA synchronous=FULL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            migrate();
            return null;
        }).get(15, TimeUnit.SECONDS);
    }

    private void migrate() throws SQLException {
        int version = 0;
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            var rs = st.executeQuery("SELECT MAX(version) FROM schema_version");
            if (rs.next()) version = rs.getInt(1);
        }
        List<List<String>> migrations = List.of(MIGRATION_1, MIGRATION_2, MIGRATION_3, MIGRATION_4);
        for (int next = version + 1; next <= migrations.size(); next++) {
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                for (String ddl : migrations.get(next - 1)) st.execute(ddl);
                st.execute("INSERT INTO schema_version (version) VALUES (" + next + ")");
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
            plugin.getLogger().info("Market DB migrated to schema v" + next);
        }
    }

    private static final List<String> MIGRATION_1 = List.of(
            """
                    CREATE TABLE listings (
                      id            TEXT PRIMARY KEY,
                      seller_uuid   TEXT NOT NULL,
                      seller_name   TEXT NOT NULL,
                      item_bytes    BLOB NOT NULL,
                      material      TEXT NOT NULL,
                      amount        INTEGER NOT NULL,
                      display_name  TEXT,
                      category      TEXT NOT NULL,
                      is_coi_item   INTEGER NOT NULL DEFAULT 0,
                      coi_pathway   TEXT,
                      coi_sequence  INTEGER,
                      price         INTEGER NOT NULL,
                      state         TEXT NOT NULL,
                      buyer_uuid    TEXT,
                      plot_id       TEXT,
                      created_at    INTEGER NOT NULL,
                      expires_at    INTEGER NOT NULL,
                      reserved_at   INTEGER,
                      sold_at       INTEGER
                    )""",
            "CREATE INDEX idx_listings_browse ON listings(state, category, created_at)",
            "CREATE INDEX idx_listings_seller ON listings(seller_uuid, state)",
            "CREATE INDEX idx_listings_coi ON listings(state, is_coi_item, coi_pathway, coi_sequence)",
            "CREATE INDEX idx_listings_expiry ON listings(state, expires_at)",
            """
                    CREATE TABLE stash_items (
                      id           TEXT PRIMARY KEY,
                      owner_uuid   TEXT NOT NULL,
                      item_bytes   BLOB NOT NULL,
                      material     TEXT NOT NULL,
                      amount       INTEGER NOT NULL,
                      display_name TEXT,
                      source       TEXT NOT NULL,
                      source_ref   TEXT,
                      created_at   INTEGER NOT NULL,
                      claimed_at   INTEGER
                    )""",
            "CREATE INDEX idx_stash_owner ON stash_items(owner_uuid, claimed_at)",
            """
                    CREATE TABLE ledger_entries (
                      id             TEXT PRIMARY KEY,
                      owner_uuid     TEXT NOT NULL,
                      gross          INTEGER NOT NULL,
                      tax            INTEGER NOT NULL,
                      net            INTEGER NOT NULL,
                      source_listing TEXT,
                      state          TEXT NOT NULL DEFAULT 'UNCLAIMED',
                      created_at     INTEGER NOT NULL,
                      claimed_at     INTEGER
                    )""",
            "CREATE INDEX idx_ledger_owner ON ledger_entries(owner_uuid, state)",
            """
                    CREATE TABLE plots (
                      plot_id       TEXT PRIMARY KEY,
                      renter_uuid   TEXT,
                      renter_name   TEXT,
                      rented_at     INTEGER,
                      paid_until    INTEGER,
                      vendor_npc_id INTEGER
                    )""",
            """
                    CREATE TABLE entrances (
                      id          TEXT PRIMARY KEY,
                      land_id     TEXT UNIQUE,
                      world       TEXT NOT NULL,
                      x           INTEGER NOT NULL,
                      y           INTEGER NOT NULL,
                      z           INTEGER NOT NULL,
                      created_by  TEXT NOT NULL,
                      created_at  INTEGER NOT NULL
                    )""",
            """
                    CREATE TABLE return_points (
                      player_uuid TEXT PRIMARY KEY,
                      location    TEXT NOT NULL,
                      entrance_id TEXT,
                      created_at  INTEGER NOT NULL
                    )""",
            """
                    CREATE TABLE courier_contracts (
                      player_uuid     TEXT PRIMARY KEY,
                      horn_item_bytes BLOB NOT NULL,
                      courier_type    TEXT NOT NULL,
                      deposited_at    INTEGER NOT NULL
                    )""",
            """
                    CREATE TABLE transactions (
                      id           INTEGER PRIMARY KEY AUTOINCREMENT,
                      type         TEXT NOT NULL,
                      actor_uuid   TEXT NOT NULL,
                      counterparty TEXT,
                      listing_id   TEXT,
                      amount       INTEGER,
                      detail       TEXT,
                      created_at   INTEGER NOT NULL
                    )""",
            """
                    CREATE TABLE daily_counters (
                      owner_uuid       TEXT NOT NULL,
                      day              TEXT NOT NULL,
                      listings_created INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (owner_uuid, day)
                    )"""
    );

    /**
     * Phase 2 (prestige plots): the pristine block state of each plot cuboid, captured
     * when an admin defines the plot and replayed on eviction. Keyed by the market.yml
     * region id, so redefining a region replaces its baseline.
     */
    private static final List<String> MIGRATION_2 = List.of(
            """
                    CREATE TABLE plot_snapshots (
                      plot_id     TEXT PRIMARY KEY,
                      world       TEXT NOT NULL,
                      min_x       INTEGER NOT NULL,
                      min_y       INTEGER NOT NULL,
                      min_z       INTEGER NOT NULL,
                      max_x       INTEGER NOT NULL,
                      max_y       INTEGER NOT NULL,
                      max_z       INTEGER NOT NULL,
                      blocks      BLOB NOT NULL,
                      created_at  INTEGER NOT NULL
                    )"""
    );

    /**
     * Phase 3 (price guide): {@code value_key} groups listings by "goods of this exact
     * sort" — a CoI ingredient id, a pathway/sequence potion, or a plain material — so
     * the Fence can quote what such things have actually been selling for. Rows written
     * before this migration keep a NULL key and simply don't contribute history.
     */
    private static final List<String> MIGRATION_3 = List.of(
            "ALTER TABLE listings ADD COLUMN value_key TEXT",
            "CREATE INDEX idx_listings_value ON listings(value_key, state, sold_at)"
    );

    /**
     * Phase 4 (stall counters): a sign on a chest inside a rented plot, selling whatever
     * the owner bound to it at the price on the sign.
     *
     * <p>Deliberately records no stock level. The stock <i>is</i> the container's contents
     * at the moment of the click — a number here could disagree with the chest, and the
     * chest is the thing the player can see.
     *
     * <p>{@code item_bytes} is null until the owner binds goods to the counter, and the
     * unique index on the sign position is what makes the in-memory cache safe to rebuild
     * from disk after a restart.
     */
    private static final List<String> MIGRATION_4 = List.of(
            """
                    CREATE TABLE plot_shops (
                      id           TEXT PRIMARY KEY,
                      plot_id      TEXT NOT NULL,
                      world        TEXT NOT NULL,
                      sign_x       INTEGER NOT NULL,
                      sign_y       INTEGER NOT NULL,
                      sign_z       INTEGER NOT NULL,
                      chest_x      INTEGER NOT NULL,
                      chest_y      INTEGER NOT NULL,
                      chest_z      INTEGER NOT NULL,
                      owner_uuid   TEXT NOT NULL,
                      owner_name   TEXT NOT NULL,
                      item_bytes   BLOB,
                      material     TEXT,
                      display_name TEXT,
                      price        INTEGER NOT NULL,
                      bundle       INTEGER NOT NULL DEFAULT 1,
                      sold_count   INTEGER NOT NULL DEFAULT 0,
                      created_at   INTEGER NOT NULL
                    )""",
            "CREATE UNIQUE INDEX idx_plot_shops_sign ON plot_shops(world, sign_x, sign_y, sign_z)",
            "CREATE INDEX idx_plot_shops_plot ON plot_shops(plot_id)"
    );

    /**
     * Blocking cache load for {@code onEnable}, bounded and instrumented.
     *
     * <p>Several services each fill a cache this way before the module can serve anyone, and
     * they queue on the one DB thread, so their timeouts add up into main-thread stall time
     * the watchdog can notice. Keeping the individual budget short and logging anything slow
     * makes a degraded disk visible in the boot log instead of as a mystery freeze.
     */
    public <T> T awaitLoad(String what, SqlWork<T> work) throws Exception {
        long started = System.nanoTime();
        try {
            return submit(work).get(5, TimeUnit.SECONDS);
        } finally {
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (ms > 1000) plugin.getLogger().warning("Market " + what + " load took " + ms + "ms");
        }
    }

    /**
     * Runs {@code work} on the DB thread. The future completes (or fails) on that thread.
     */
    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (executor.isShutdown()) {
            future.completeExceptionally(new IllegalStateException("Market DB is shut down"));
            return future;
        }
        executor.submit(() -> {
            try {
                future.complete(work.run(connection));
            } catch (Throwable t) {
                if (!(t instanceof ControlFlow)) {
                    plugin.getLogger().severe("Market DB operation failed: " + t.getMessage());
                }
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Runs {@code work} on the DB thread inside an explicit transaction (rollback on
     * any exception), then hands the result to {@code onMain} on the main thread.
     * On failure, {@code onError} (nullable) runs on the main thread instead.
     */
    public <T> void transactionThenMain(SqlWork<T> work, Consumer<T> onMain, Consumer<Throwable> onError) {
        submit(conn -> {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            boolean settled = false;
            try {
                T result = work.run(conn);
                conn.commit();
                settled = true;
                return result;
            } catch (Throwable t) {
                // Catch Throwable, not just SQLException/RuntimeException: restoring
                // auto-commit on a connection with an open transaction COMMITS it (JDBC
                // spec), so an escaping Error would turn a half-finished sale into a
                // durable one. Roll back first, and only restore auto-commit once the
                // transaction is definitely settled.
                try {
                    conn.rollback();
                    settled = true;
                } catch (SQLException rollbackFailed) {
                    plugin.getLogger().severe("Market DB rollback failed, connection left in transaction: "
                            + rollbackFailed.getMessage());
                }
                throw t;
            } finally {
                if (settled) conn.setAutoCommit(auto);
            }
        }).whenComplete((result, error) -> thenMain(result, error, onMain, onError));
    }

    /**
     * Non-transactional read followed by a main-thread continuation.
     */
    public <T> void submitThenMain(SqlWork<T> work, Consumer<T> onMain, Consumer<Throwable> onError) {
        submit(work).whenComplete((result, error) -> thenMain(result, error, onMain, onError));
    }

    /**
     * Hands a finished DB result to its continuation on the main thread.
     *
     * <p>The scheduler refuses new tasks once the plugin is disabling, and a shutdown drain
     * completes exactly the work most likely to be mid-flight — so the refusal is logged as
     * a dropped continuation rather than thrown back into the DB thread, where it would
     * abort the rest of the drain.
     */
    private <T> void thenMain(T result, Throwable error, Consumer<T> onMain, Consumer<Throwable> onError) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    if (onError != null) onError.accept(error);
                } else {
                    onMain.accept(result);
                }
            });
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            plugin.getLogger().warning("Market DB continuation dropped during shutdown"
                    + (error != null ? " (after error " + error + ")" : "") + " — the write itself committed");
        }
    }

    /**
     * Drains pending work (10s budget) and closes the connection. Call from onDisable only.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Market DB executor did not drain within 10s — forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to close market.db: " + e.getMessage());
        }
    }
}
