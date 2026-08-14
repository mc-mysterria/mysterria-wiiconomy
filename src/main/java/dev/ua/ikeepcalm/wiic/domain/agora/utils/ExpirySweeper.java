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
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
    private BukkitTask task;

    public ExpirySweeper(WIIC plugin, MarketConfig config, MarketDatabase db) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
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
        db.submit(conn -> {
            int released = ListingDao.releaseStaleReservations(conn, now - config.reservationTimeoutMs());
            if (released > 0) plugin.getLogger().warning("Market sweeper released " + released + " stale reservations");

            List<Listing> expirable = ListingDao.findExpirable(conn, now, EXPIRE_BATCH);
            for (Listing listing : expirable) {
                boolean auto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    if (ListingDao.expire(conn, listing.id())) {
                        StashDao.insert(conn, new StashItem(UUID.randomUUID(), listing.sellerUuid(),
                                listing.itemBytes(), listing.material(), listing.amount(), listing.displayName(),
                                StashItem.SOURCE_EXPIRED, listing.id().toString(), now));
                        TransactionDao.log(conn, "EXPIRE", listing.sellerUuid(), null, listing.id(), listing.price(), null);
                    }
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    plugin.getLogger().severe("Failed to expire listing " + listing.id() + ": " + e);
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
}
