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
import dev.ua.ikeepcalm.wiic.utils.MysterriaAuditBridge;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.math.BigDecimal;
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
        Set<String> paidAttempts = entries.stream()
                .filter(entry -> entry.type() == MarketJournal.Type.BUY_PAID)
                .map(MarketJournal.Entry::id)
                .collect(Collectors.toSet());

        try {
            db.submit(conn -> {
                for (MarketJournal.Entry entry : entries) {
                    // Markers and stash claims carry no repair of their own; they are read
                    // through their parent entry and pruned with it.
                    if (entry.type() == MarketJournal.Type.CLAIM_DEPOSITED
                            || entry.type() == MarketJournal.Type.BUY_PAID
                            || entry.type() == MarketJournal.Type.STASH_CLAIM) {
                        continue;
                    }
                    Runnable afterCommit;
                    // One transaction per entry: a half-applied repair (listing SOLD with no
                    // stash row) would be worse than the interruption it is fixing.
                    boolean auto = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        afterCommit = recover(conn, entry, depositedBatches, paidAttempts);
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
                for (String attemptId : paidAttempts) journal.remove(attemptId);
                return null;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Market journal recovery did not complete: " + e);
        }
    }

    /** @return work to run after the repair commits (a Vault refund), or null. */
    private @Nullable Runnable recover(Connection conn, MarketJournal.Entry entry,
                                       Set<String> depositedBatches, Set<String> paidAttempts) throws Exception {
        return switch (entry.type()) {
            case LIST -> {
                yield recoverList(conn, entry);
            }
            case BUY -> recoverBuy(conn, entry, paidAttempts);
            case CLAIM -> recoverClaim(conn, entry, depositedBatches);
            case BUY_PAID, CLAIM_DEPOSITED, STASH_CLAIM -> null;
        };
    }

    /** Crash between fee withdraw and listing insert: the item exists only in the journal payload. */
    private @Nullable Runnable recoverList(Connection conn, MarketJournal.Entry entry) throws Exception {
        UUID listingId = UUID.fromString(entry.id());
        if (ListingDao.findById(conn, listingId) != null) return null; // committed before the crash
        if (entry.payload() == null) return null;
        ItemStack item = ItemStack.deserializeBytes(entry.payload());
        StashDao.insert(conn, new StashItem(UUID.randomUUID(), entry.player(), entry.payload(),
                item.getType(), item.getAmount(), null,
                StashItem.SOURCE_RECOVERY, entry.id(), System.currentTimeMillis()));
        TransactionDao.log(conn, "RECOVERY", entry.player(), null, listingId, entry.amount(),
                "unlisted item restored to stash");
        plugin.getLogger().warning("Recovered unlisted item for " + entry.player() + " into their stash");
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("agora-listing", listingId);
        return () -> MysterriaAuditBridge.emit("agora.listing.item_recovered", true,
                entry.player(), entry.player(), listingId, identity, "unlisted item restored to stash",
                MysterriaAuditBridge.metadata(Map.of("listing_id", listingId.toString()),
                        MysterriaAuditBridge.itemMetadata(entry.payload())));
    }

    /** Crash between the buyer's withdraw and the sale commit: finish the sale or refund. */
    private @Nullable Runnable recoverBuy(Connection conn, MarketJournal.Entry entry,
                                          Set<String> paidAttempts) throws Exception {
        // Entries predating the intent/proof split carried the listing id directly and were
        // only ever written after a successful withdraw, so they count as paid.
        boolean legacy = entry.ref() == null;
        UUID listingId = UUID.fromString(legacy ? entry.id() : entry.ref());
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("agora-purchase", entry.id());
        Listing listing = ListingDao.findById(conn, listingId);
        if (listing == null) return null;

        if (!legacy && !paidAttempts.contains(entry.id())) {
            // Intent with no proof: the crash landed around the withdraw and nothing can say
            // whether the money left. Hand over no goods and issue no refund — either would
            // invent value. Release the hold so the listing goes back on sale, and make the
            // ambiguity loud enough that staff can reconcile the one buyer it might affect.
            if (listing.state() == ListingState.PENDING_PAYMENT && entry.player().equals(listing.buyerUuid())) {
                ListingDao.releaseReservation(conn, listingId, entry.player());
            }
            TransactionDao.log(conn, "RECOVERY", entry.player(), null, listingId, entry.amount(),
                    "unproven purchase, released - verify buyer balance");
            plugin.getLogger().severe("Market purchase " + listingId + " by " + entry.player()
                    + " for " + entry.amount() + " coppets was interrupted before payment could be proven."
                    + " No goods delivered and no refund issued — check whether the withdraw landed.");
            return () -> MysterriaAuditBridge.emit("agora.purchase.recovery_unproven", false,
                    entry.player(), entry.player(), listingId, identity,
                    "payment unproven; reservation released", MysterriaAuditBridge.moneyMetadata(0,
                            Map.of("listing_id", listingId.toString())));
        }

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
                    StashItem.SOURCE_PURCHASE, listingId.toString(), now));
            LedgerDao.insert(conn, new LedgerEntry(UUID.randomUUID(), listing.sellerUuid(),
                    price, tax, price - tax, listingId, now));
            TransactionDao.log(conn, "BUY", entry.player(), listing.sellerUuid(), listingId, price, "journal recovery");
            plugin.getLogger().warning("Recovered interrupted sale " + listingId + " for buyer " + entry.player());
            return () -> MysterriaAuditBridge.emit("agora.purchase.recovered", true,
                    entry.player(), listing.sellerUuid(), listingId, identity,
                    "interrupted sale committed by recovery", MysterriaAuditBridge.moneyMetadata(-price,
                            MysterriaAuditBridge.metadata(Map.of("listing_id", listingId.toString(),
                                            "tax", tax, "net", price - tax),
                                    MysterriaAuditBridge.itemMetadata(listing.itemBytes()))));
        }
        // Reservation was already released (sweeper or unknown state) — the withdraw must be
        // refunded. The audit row commits first; the money moves in the post-commit hook so a
        // rollback can never leave a refund nothing accounts for.
        TransactionDao.log(conn, "RECOVERY", entry.player(), null, listingId, entry.amount(), "buy refund");
        UUID buyer = entry.player();
        long amount = entry.amount();
        return () -> {
            BigDecimal balanceBefore = balance(buyer);
            VaultUtil.Payment payment = VaultUtil.deposit(buyer, amount);
            boolean refunded = payment.succeeded();
            if (!refunded) {
                plugin.getLogger().severe("Recovery refund of " + amount + " coppets to "
                        + buyer + " FAILED — manual repair needed");
            } else {
                plugin.getLogger().warning("Recovery refunded " + amount + " coppets to " + buyer);
            }
            MysterriaAuditBridge.emit("agora.purchase.recovery_refunded", refunded,
                    buyer, buyer, listingId, identity,
                    refunded ? "interrupted purchase refunded by recovery"
                            : "recovery refund failed; manual repair needed",
                    MysterriaAuditBridge.moneyMetadata(refunded ? amount : 0,
                            balanceBefore, balance(buyer), Map.of("listing_id", listingId.toString(),
                            "payment", payment.name())));
        };
    }

    /** Crash during a ledger claim: the CLAIM_DEPOSITED marker decides the direction. */
    private @Nullable Runnable recoverClaim(Connection conn, MarketJournal.Entry entry,
                                            Set<String> depositedBatches) throws Exception {
        UUID owner = entry.player();
        if (!LedgerDao.hasClaiming(conn, owner)) return null; // claim finished or was settled already
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("ledger-claim", entry.id());
        if (depositedBatches.contains(entry.id())) {
            LedgerDao.finishClaim(conn, owner, System.currentTimeMillis());
            TransactionDao.log(conn, "CLAIM_PROCEEDS", owner, null, null, entry.amount(), "journal recovery");
            plugin.getLogger().warning("Recovered deposited ledger claim of " + entry.amount() + " for " + owner);
            return () -> MysterriaAuditBridge.emit("ledger.claim_recovered", true,
                    owner, owner, null, identity, "deposited claim finalized by recovery",
                    MysterriaAuditBridge.moneyMetadata(entry.amount(),
                            Map.of("claim_id", entry.id())));
        } else {
            // Intent with no proof: the crash or a provider failure landed around the deposit
            // and nothing can say whether the money arrived. Reverting would let the owner
            // claim a batch that may already be paid; finishing would invent a payout. Leave
            // the rows CLAIMING, which withholds them, and make it loud enough for staff to
            // reconcile the one owner it might affect.
            TransactionDao.log(conn, "RECOVERY", owner, null, null, entry.amount(),
                    "unproven claim withheld - verify owner balance");
            plugin.getLogger().severe("Ledger claim " + entry.id() + " of " + entry.amount() + " coppets for "
                    + owner + " was interrupted before the deposit could be proven. Rows left CLAIMING and"
                    + " no payout repeated. Check whether the deposit landed.");
            return () -> MysterriaAuditBridge.emit("ledger.claim_recovery_unproven", false,
                    owner, owner, null, identity, "deposit unproven; claim withheld",
                    MysterriaAuditBridge.moneyMetadata(0, Map.of("claim_id", entry.id(),
                            "claim_sum", entry.amount())));
        }
    }

    private static BigDecimal balance(UUID uuid) {
        return VaultUtil.balance(uuid);
    }
}
