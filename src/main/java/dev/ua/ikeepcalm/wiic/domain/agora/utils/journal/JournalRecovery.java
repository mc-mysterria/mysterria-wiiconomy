package dev.ua.ikeepcalm.wiic.domain.agora.utils.journal;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.LedgerDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.LedgerEntry;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.Listing;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ListingState;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Replays surviving {@link MarketJournal} entries at startup, repairing flows the
 * last shutdown cut in half. Every repair is idempotent — each entry carries the
 * flow's UUID, so "did the DB commit land?" is a primary-key lookup.
 *
 * <p>Runs once from {@code MarketModule.enable()}, blocking until done, before any
 * player can touch the market. Vault is available at that point (the module is
 * wired after {@code setupEconomy()}).
 */
public class JournalRecovery {

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final MarketJournal journal;

    public JournalRecovery(WIIC plugin, MarketConfig config, MarketDatabase db, MarketJournal journal) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.journal = journal;
    }

    public void run() {
        List<MarketJournal.Entry> entries = journal.all();
        if (entries.isEmpty()) return;
        plugin.getLogger().warning("Market journal has " + entries.size()
                + " surviving entries — repairing interrupted flows");

        // Snapshot the deposit proofs before anything is pruned. journal.remove(id) drops
        // every type sharing that id, so a CLAIM's CLAIM_DEPOSITED marker would disappear
        // the moment its own batch was handled — reading the live journal mid-loop makes
        // the outcome depend on iteration order.
        Set<String> depositedBatches = entries.stream()
                .filter(entry -> entry.type() == MarketJournal.Type.CLAIM_DEPOSITED)
                .map(MarketJournal.Entry::id)
                .collect(Collectors.toSet());

        try {
            db.submit(conn -> {
                for (MarketJournal.Entry entry : entries) {
                    // Markers and stash claims carry no repair of their own; they are read
                    // through their parent entry and pruned with it.
                    if (entry.type() == MarketJournal.Type.CLAIM_DEPOSITED
                            || entry.type() == MarketJournal.Type.STASH_CLAIM) {
                        continue;
                    }
                    Runnable afterCommit;
                    // One transaction per entry: a half-applied repair (listing SOLD with no
                    // stash row) would be worse than the interruption it is fixing.
                    boolean auto = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        afterCommit = recover(conn, entry, depositedBatches);
                        conn.commit();
                    } catch (Exception e) {
                        conn.rollback();
                        plugin.getLogger().severe("Market recovery failed for " + entry.type() + " "
                                + entry.id() + ": " + e + " — entry kept for manual inspection");
                        continue;
                    } finally {
                        conn.setAutoCommit(auto);
                    }
                    journal.remove(entry.id());
                    // Money moves only once the repair is durable, so a rollback can never
                    // leave a refund that no row accounts for.
                    if (afterCommit != null) afterCommit.run();
                }
                // Markers whose parent entry never made it to disk would otherwise linger.
                for (String batchId : depositedBatches) journal.remove(batchId);
                return null;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Market journal recovery did not complete: " + e);
        }
    }

    /** @return work to run after the repair commits (a Vault refund), or null. */
    private @Nullable Runnable recover(Connection conn, MarketJournal.Entry entry,
                                       Set<String> depositedBatches) throws Exception {
        return switch (entry.type()) {
            case LIST -> {
                recoverList(conn, entry);
                yield null;
            }
            case BUY -> recoverBuy(conn, entry);
            case CLAIM -> {
                recoverClaim(conn, entry, depositedBatches);
                yield null;
            }
            case CLAIM_DEPOSITED, STASH_CLAIM -> null;
        };
    }

    /** Crash between fee withdraw and listing insert: the item exists only in the journal payload. */
    private void recoverList(Connection conn, MarketJournal.Entry entry) throws Exception {
        UUID listingId = UUID.fromString(entry.id());
        if (ListingDao.findById(conn, listingId) != null) return; // committed before the crash
        if (entry.payload() == null) return;
        ItemStack item = ItemStack.deserializeBytes(entry.payload());
        StashDao.insert(conn, new StashItem(UUID.randomUUID(), entry.player(), entry.payload(),
                item.getType(), item.getAmount(), null,
                StashItem.SOURCE_RECOVERY, entry.id(), System.currentTimeMillis()));
        TransactionDao.log(conn, "RECOVERY", entry.player(), null, listingId, entry.amount(),
                "unlisted item restored to stash");
        plugin.getLogger().warning("Recovered unlisted item for " + entry.player() + " into their stash");
    }

    /** Crash between the buyer's withdraw and the sale commit: finish the sale or refund. */
    private @Nullable Runnable recoverBuy(Connection conn, MarketJournal.Entry entry) throws Exception {
        UUID listingId = UUID.fromString(entry.id());
        Listing listing = ListingDao.findById(conn, listingId);
        if (listing == null) return null;

        if (listing.state() == ListingState.SOLD && entry.player().equals(listing.buyerUuid())) {
            return null; // commit landed before the crash
        }
        if (listing.state() == ListingState.PENDING_PAYMENT && entry.player().equals(listing.buyerUuid())) {
            // Money was taken; complete the sale exactly as commitSale would have.
            long price = listing.price();
            long tax = config.saleTax(price);
            long now = System.currentTimeMillis();
            ListingDao.markSold(conn, listingId, entry.player(), now);
            StashDao.insert(conn, new StashItem(UUID.randomUUID(), entry.player(), listing.itemBytes(),
                    listing.material(), listing.amount(), listing.displayName(),
                    StashItem.SOURCE_PURCHASE, entry.id(), now));
            LedgerDao.insert(conn, new LedgerEntry(UUID.randomUUID(), listing.sellerUuid(),
                    price, tax, price - tax, listingId, now));
            TransactionDao.log(conn, "BUY", entry.player(), listing.sellerUuid(), listingId, price, "journal recovery");
            plugin.getLogger().warning("Recovered interrupted sale " + listingId + " for buyer " + entry.player());
            return null;
        }
        // Reservation was already released (sweeper or unknown state) — the withdraw must be
        // refunded. The audit row commits first; the money moves in the post-commit hook so a
        // rollback can never leave a refund nothing accounts for.
        TransactionDao.log(conn, "RECOVERY", entry.player(), null, listingId, entry.amount(), "buy refund");
        UUID buyer = entry.player();
        long amount = entry.amount();
        return () -> {
            if (!VaultUtil.deposit(buyer, amount)) {
                plugin.getLogger().severe("Recovery refund of " + amount + " coppets to "
                        + buyer + " FAILED — manual repair needed");
            } else {
                plugin.getLogger().warning("Recovery refunded " + amount + " coppets to " + buyer);
            }
        };
    }

    /** Crash during a ledger claim: the CLAIM_DEPOSITED marker decides the direction. */
    private void recoverClaim(Connection conn, MarketJournal.Entry entry,
                              Set<String> depositedBatches) throws Exception {
        UUID owner = entry.player();
        if (!LedgerDao.hasClaiming(conn, owner)) return; // claim finished or was reverted already
        if (depositedBatches.contains(entry.id())) {
            LedgerDao.finishClaim(conn, owner, System.currentTimeMillis());
            TransactionDao.log(conn, "CLAIM_PROCEEDS", owner, null, null, entry.amount(), "journal recovery");
            plugin.getLogger().warning("Recovered deposited ledger claim of " + entry.amount() + " for " + owner);
        } else {
            LedgerDao.revertClaim(conn, owner);
            plugin.getLogger().warning("Reverted unproven ledger claim of " + entry.amount() + " for " + owner);
        }
    }
}
