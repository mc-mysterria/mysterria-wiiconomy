package dev.ua.ikeepcalm.wiic.domain.agora.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.DailyCounterDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.Listing;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.utils.MysterriaAuditBridge;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Periodic market housekeeping: expires overdue listings into their sellers'
 * stashes, releases reservations whose purchase thread died, and (once, at
 * startup) purges stale audit rows and daily counters. All DB work runs on the
 * DB thread; the Bukkit timer only submits.
 */
public class ExpirySweeper {

    private static final int EXPIRE_BATCH = 100;

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    /** When set, reservations whose purchase is still journaled are never released. */
    private final @Nullable MarketJournal journal;
    private BukkitTask task;

    public ExpirySweeper(WIIC plugin, MarketConfig config, MarketDatabase db) {
        this(plugin, config, db, null);
    }

    public ExpirySweeper(WIIC plugin, MarketConfig config, MarketDatabase db, @Nullable MarketJournal journal) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.journal = journal;
    }

    public void start() {
        long interval = config.sweeperIntervalTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, interval, interval);
        db.submit(conn -> {
            long cutoff = System.currentTimeMillis() - config.transactionRetentionDays() * 24L * 60L * 60L * 1000L;
            int purgedTx = TransactionDao.purgeOlderThan(conn, cutoff);
            int purgedCounters = DailyCounterDao.purgeBefore(conn, LocalDate.now().minusDays(7).toString());
            if (purgedTx + purgedCounters > 0) {
                plugin.getLogger().info("Market housekeeping purged " + purgedTx + " audit rows, "
                        + purgedCounters + " counter rows");
            }
            return null;
        });
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        // A surviving BUY entry means the purchase is still in flight, or its money outcome
        // is unknown and waits for startup recovery to hold it. Releasing that listing would
        // let a second buyer take goods the first one may have paid for.
        Set<UUID> journaled = journal == null ? Set.of() : journal.all().stream()
                .filter(entry -> entry.type() == MarketJournal.Type.BUY)
                // Legacy BUY entries carried the listing id as their own id.
                .map(entry -> UUID.fromString(entry.ref() != null ? entry.ref() : entry.id()))
                .collect(Collectors.toSet());
        db.submit(conn -> {
            int released = ListingDao.releaseStaleReservations(conn, now - config.reservationTimeoutMs(), journaled);
            if (released > 0) {
                plugin.getLogger().warning("Market sweeper released " + released + " stale reservations");
                // The release is one UPDATE that reports only a count, so the row is a batch row.
                Map<String, Object> batch = new LinkedHashMap<>();
                batch.put("released", released);
                batch.put("timeout_ms", config.reservationTimeoutMs());
                batch.put("journal_protected", journaled.size());
                MysterriaAuditBridge.emit("agora.reservations.released", true, null, null, null,
                        MysterriaAuditBridge.randomIdentity("sweeper"), "stale reservations released", batch);
            }

            List<Listing> expirable = ListingDao.findExpirable(conn, now, EXPIRE_BATCH);
            for (Listing listing : expirable) {
                boolean auto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                boolean expired = false;
                try {
                    if (ListingDao.expire(conn, listing.id())) {
                        StashDao.insert(conn, new StashItem(UUID.randomUUID(), listing.sellerUuid(),
                                listing.itemBytes(), listing.material(), listing.amount(), listing.displayName(),
                                StashItem.SOURCE_EXPIRED, listing.id().toString(), now));
                        TransactionDao.log(conn, "EXPIRE", listing.sellerUuid(), null, listing.id(), listing.price(), null);
                        expired = true;
                    }
                    conn.commit();
                    if (expired) emitExpiry(listing, true, null);
                } catch (Exception e) {
                    conn.rollback();
                    plugin.getLogger().severe("Failed to expire listing " + listing.id() + ": " + e);
                    emitExpiry(listing, false, "expiry failed: " + e.getClass().getSimpleName());
                } finally {
                    conn.setAutoCommit(auto);
                }
            }
            if (!expirable.isEmpty()) {
                plugin.getLogger().info("Market sweeper expired " + expirable.size() + " listings to stashes");
            }
            return null;
        });
    }

    /**
     * Row for a listing moved to the seller's stash (or that could not be). Runs on the DB
     * thread after the commit; the item projection is read from the serialized bytes.
     */
    private static void emitExpiry(Listing listing, boolean success, @Nullable String failure) {
        Map<String, Object> metadata = new LinkedHashMap<>(MysterriaAuditBridge.itemMetadata(listing.itemBytes()));
        metadata.put("listing_id", listing.id().toString());
        metadata.put("price", listing.price());
        metadata.put("destination", "stash");
        metadata.put("stash_source", StashItem.SOURCE_EXPIRED);
        if (listing.plotId() != null) metadata.put("plot_id", listing.plotId());
        MysterriaAuditBridge.emit(success ? "agora.listing.expired" : "agora.listing.expire_failed", success, null,
                listing.sellerUuid(), null, MysterriaAuditBridge.identity("agora-listing", listing.id()),
                success ? "listing expired to stash" : failure, metadata);
    }
}
