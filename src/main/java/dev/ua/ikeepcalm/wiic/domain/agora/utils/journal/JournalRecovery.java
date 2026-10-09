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
 *
 * <p>A purchase whose money outcome cannot be proven is never pruned: its listing is held
 * as {@code PAYMENT_HELD} and its entry is kept, so every later startup reports the same
 * buyer, listing, attempt and amount until staff settle it. A purchase whose refund the
 * provider refused is kept and held the same way, but reported as a known refund owed.
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
        Set<String> refundAttempts = entries.stream()
                .filter(entry -> entry.type() == MarketJournal.Type.BUY_REFUND)
                .map(MarketJournal.Entry::id)
                .collect(Collectors.toSet());
        Set<String> refusedAttempts = entries.stream()
                .filter(entry -> entry.type() == MarketJournal.Type.BUY_REFUND_REFUSED)
                .map(MarketJournal.Entry::id)
                .collect(Collectors.toSet());

        try {
            db.submit(conn -> {
                for (MarketJournal.Entry entry : entries) {
                    // Markers and stash claims carry no repair of their own; they are read
                    // through their parent entry and pruned with it.
                    if (entry.type() == MarketJournal.Type.CLAIM_DEPOSITED
                            || entry.type() == MarketJournal.Type.BUY_PAID
                            || entry.type() == MarketJournal.Type.BUY_REFUND
                            || entry.type() == MarketJournal.Type.BUY_REFUND_REFUSED
                            || entry.type() == MarketJournal.Type.STASH_CLAIM) {
                        continue;
                    }
                    Repair repair;
                    // One transaction per entry: a half-applied repair (listing SOLD with no
                    // stash row) would be worse than the interruption it is fixing.
                    boolean auto = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        repair = recover(conn, entry, depositedBatches, paidAttempts, refundAttempts,
                                refusedAttempts);
                        conn.commit();
                    } catch (Exception e) {
                        conn.rollback();
                        plugin.getLogger().severe("Market recovery failed for " + entry.type() + " "
                                + entry.id() + ": " + e + " — entry kept for manual inspection");
                        continue;
                    } finally {
                        conn.setAutoCommit(auto);
                    }
                    if (repair.prune()) journal.remove(entry.id());
                    // Money moves only once the repair is durable, so a rollback can never
                    // leave a refund that no row accounts for.
                    if (repair.afterCommit() != null) repair.afterCommit().run();
                }
                // Markers whose parent entry never made it to disk would otherwise linger.
                // remove(id) drops every type sharing the id, so a parent that was kept
                // (unsettled, or its repair rolled back) must keep its markers too.
                for (String batchId : depositedBatches) {
                    if (!journal.contains(MarketJournal.Type.CLAIM, batchId)) journal.remove(batchId);
                }
                // A refused refund is a debt whatever else survived, so its id is never pruned here.
                for (String attemptId : paidAttempts) {
                    if (!journal.contains(MarketJournal.Type.BUY, attemptId)
                            && !refusedAttempts.contains(attemptId)) journal.remove(attemptId);
                }
                for (String attemptId : refundAttempts) {
                    if (!journal.contains(MarketJournal.Type.BUY, attemptId)
                            && !refusedAttempts.contains(attemptId)) journal.remove(attemptId);
                }
                for (MarketJournal.Entry entry : entries) {
                    if (entry.type() == MarketJournal.Type.BUY_REFUND_REFUSED
                            && !journal.contains(MarketJournal.Type.BUY, entry.id())) {
                        plugin.getLogger().severe("REFUND OWED: market purchase " + entry.ref() + " by "
                                + entry.player() + " for " + entry.amount() + " coppets (attempt " + entry.id()
                                + "): refund refused and its purchase entry is missing. Marker kept; the refund"
                                + " will not be retried automatically; repay the buyer by hand.");
                    }
                }
                return null;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Market journal recovery did not complete: " + e);
        }
    }

    /**
     * What a committed repair leaves behind.
     *
     * @param prune      whether the entry is settled and may leave the journal now
     * @param afterCommit work to run after the repair commits (a Vault refund or an audit row), or null
     */
    private record Repair(boolean prune, @Nullable Runnable afterCommit) {
        static final Repair DONE = new Repair(true, null);
        static final Repair KEEP = new Repair(false, null);
    }

    private Repair recover(Connection conn, MarketJournal.Entry entry, Set<String> depositedBatches,
                           Set<String> paidAttempts, Set<String> refundAttempts,
                           Set<String> refusedAttempts) throws Exception {
        return switch (entry.type()) {
            case LIST -> new Repair(true, recoverList(conn, entry));
            case BUY -> recoverBuy(conn, entry, paidAttempts, refundAttempts, refusedAttempts);
            case CLAIM -> new Repair(true, recoverClaim(conn, entry, depositedBatches));
            case BUY_PAID, BUY_REFUND, BUY_REFUND_REFUSED, CLAIM_DEPOSITED, STASH_CLAIM -> Repair.DONE;
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

    /** Crash between the buyer's withdraw and the sale commit: finish the sale, refund, or hold. */
    private Repair recoverBuy(Connection conn, MarketJournal.Entry entry, Set<String> paidAttempts,
                              Set<String> refundAttempts, Set<String> refusedAttempts) throws Exception {
        // Entries predating the intent/proof split carried the listing id directly and were
        // only ever written after a successful withdraw, so they count as paid.
        boolean legacy = entry.ref() == null;
        UUID listingId = UUID.fromString(legacy ? entry.id() : entry.ref());
        Listing listing = ListingDao.findById(conn, listingId);
        boolean refused = refusedAttempts.contains(entry.id());
        if (listing == null) {
            if (!refused) return Repair.DONE;
            plugin.getLogger().severe("REFUND OWED: market purchase " + listingId + " by " + entry.player() + " for "
                    + entry.amount() + " coppets (attempt " + entry.id() + "): refund refused and the listing row"
                    + " is gone. Entry kept; the refund will not be retried automatically; repay the buyer by hand.");
            return Repair.KEEP;
        }
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("agora-purchase", entry.id());
        UUID buyer = entry.player();
        boolean ownedByBuyer = buyer.equals(listing.buyerUuid());

        if (refused) {
            // The buyer paid and the provider refused the refund: a known debt. Repeating the
            // deposit is for staff to decide, and the goods must not follow a refund attempt.
            return holdForStaff(conn, entry, listing, identity, true, "refund refused");
        }
        if (refundAttempts.contains(entry.id())) {
            // A refund was attempted and its outcome never reached disk. Refunding again could
            // pay twice; completing the sale would hand over goods that may already be paid back.
            return holdForStaff(conn, entry, listing, identity, false, "refund outcome unknown");
        }

        if (!legacy && !paidAttempts.contains(entry.id())) {
            // Intent with no proof: the crash or a provider failure landed around the withdraw
            // and nothing can say whether the money left. Hand over no goods and issue no
            // refund, since either would invent value, and keep the goods off the market. A
            // listing that was already released (older builds did that here) cannot be held,
            // but the entry is still kept so the attempt stays on record for staff.
            return holdForStaff(conn, entry, listing, identity, false, "withdraw outcome unknown");
        }

        if (listing.state() == ListingState.SOLD && ownedByBuyer) {
            return Repair.DONE; // commit landed before the crash
        }
        if (listing.state() == ListingState.PENDING_PAYMENT && ownedByBuyer) {
            // Money was taken; complete the sale exactly as commitSale would have. The CAS
            // makes this happen once: a repeat run finds the listing SOLD.
            long price = listing.price();
            long tax = config.saleTax(price);
            long now = System.currentTimeMillis();
            ListingDao.markSold(conn, listingId, buyer, now);
            StashDao.insert(conn, new StashItem(UUID.randomUUID(), buyer, listing.itemBytes(),
                    listing.material(), listing.amount(), listing.displayName(),
                    StashItem.SOURCE_PURCHASE, listingId.toString(), now));
            LedgerDao.insert(conn, new LedgerEntry(UUID.randomUUID(), listing.sellerUuid(),
                    price, tax, price - tax, listingId, now));
            TransactionDao.log(conn, "BUY", buyer, listing.sellerUuid(), listingId, price, "journal recovery");
            plugin.getLogger().warning("Recovered interrupted sale " + listingId + " for buyer " + buyer);
            return new Repair(true, () -> MysterriaAuditBridge.emit("agora.purchase.recovered", true,
                    buyer, listing.sellerUuid(), listingId, identity,
                    "interrupted sale committed by recovery", MysterriaAuditBridge.moneyMetadata(-price,
                            MysterriaAuditBridge.metadata(Map.of("listing_id", listingId.toString(),
                                            "tax", tax, "net", price - tax),
                                    MysterriaAuditBridge.itemMetadata(listing.itemBytes())))));
        }
        if (listing.state() == ListingState.PAYMENT_HELD && ownedByBuyer) {
            // Paid but held: nothing in this build holds a paid attempt without a refund
            // marker, so this is outside what recovery can reason about. Leave it for staff.
            return holdForStaff(conn, entry, listing, identity, false, "paid purchase found held");
        }
        // Reservation was already released (sweeper or unknown state), so the withdraw must be
        // refunded. The audit row commits first; the money moves in the post-commit hook so a
        // rollback can never leave a refund nothing accounts for.
        TransactionDao.log(conn, "RECOVERY", buyer, null, listingId, entry.amount(),
                "buy refund attempt " + entry.id());
        return new Repair(false, () -> refundReleased(entry, listingId, identity));
    }

    /**
     * Refunds a paid purchase whose listing was released. The {@code BUY_REFUND} marker goes
     * to disk first, so a crash or provider throw during the deposit can never be refunded
     * again: the next startup finds the marker and holds instead. A refused deposit adds a
     * {@code BUY_REFUND_REFUSED} marker and keeps the entry as the record of what is owed.
     */
    private void refundReleased(MarketJournal.Entry entry, UUID listingId,
                                MysterriaAuditBridge.AuditIdentity identity) {
        UUID buyer = entry.player();
        long amount = entry.amount();
        try {
            journal.append(MarketJournal.Type.BUY_REFUND, entry.id(), buyer, amount, null, listingId.toString());
        } catch (IllegalStateException e) {
            // Nothing moved yet, so the next startup may safely try again.
            plugin.getLogger().severe("Recovery refund of " + amount + " coppets to " + buyer + " for listing "
                    + listingId + " skipped: refund marker could not be written (" + e.getMessage()
                    + "). Entry kept; the next startup retries.");
            return;
        }
        // A provider exception is reported by the checked helper and must not abort the
        // remaining recovery entries.
        BigDecimal balanceBefore = VaultUtil.balance(buyer);
        VaultUtil.Payment payment = VaultUtil.depositChecked(buyer, amount, "recovery refund for listing " + listingId);
        boolean refunded = payment.succeeded();
        MysterriaAuditBridge.emit("agora.purchase.recovery_refunded", refunded,
                buyer, buyer, listingId, identity,
                refunded ? "interrupted purchase refunded by recovery"
                        : payment == VaultUtil.Payment.INDETERMINATE
                        ? "recovery refund outcome unknown; manual reconciliation needed"
                        : "recovery refund failed; manual repair needed",
                MysterriaAuditBridge.moneyMetadata(refunded ? amount : 0,
                        balanceBefore, VaultUtil.balance(buyer), Map.of("listing_id", listingId.toString(),
                        "payment", payment.name())));
        switch (payment) {
            case SUCCESS -> {
                plugin.getLogger().warning("Recovery refunded " + amount + " coppets to " + buyer);
                journal.remove(entry.id());
            }
            case FAILED -> {
                // Refused is a known debt, not a settled refund: keep the entry as the record
                // of what is owed and never repeat the deposit on a later startup.
                String owed = "REFUND OWED: recovery refund of " + amount + " coppets to " + buyer + " for listing "
                        + listingId + " (attempt " + entry.id() + ") was refused. Entry kept and the refund will"
                        + " not be retried automatically; repay the buyer by hand.";
                try {
                    journal.append(MarketJournal.Type.BUY_REFUND_REFUSED, entry.id(), buyer, amount, null,
                            listingId.toString());
                    plugin.getLogger().severe(owed);
                } catch (IllegalStateException e) {
                    plugin.getLogger().severe(owed + " The refused marker could not be written (" + e.getMessage()
                            + "), so later startups will report the refund as uncertain; this log line is the outcome.");
                }
            }
            case INDETERMINATE -> plugin.getLogger().severe("UNCERTAIN: recovery refund of " + amount
                    + " coppets to " + buyer + " for listing " + listingId + " (attempt " + entry.id()
                    + ") may or may not have landed. Entry kept and the refund will not be retried;"
                    + " check the buyer's balance and settle by hand.");
        }
    }

    /**
     * Keeps an attempt whose money outcome is unknown, or whose refund was refused
     * ({@code refundOwed}): holds the listing if this buyer still has it reserved, and keeps the
     * journal entry so the next startup reports it again. The audit rows are written once, on
     * the transition, rather than on every startup.
     */
    private Repair holdForStaff(Connection conn, MarketJournal.Entry entry, Listing listing,
                                MysterriaAuditBridge.AuditIdentity identity,
                                boolean refundOwed, String reason) throws Exception {
        UUID buyer = entry.player();
        boolean ownedByBuyer = buyer.equals(listing.buyerUuid());
        boolean held = ownedByBuyer && listing.state() == ListingState.PAYMENT_HELD;
        boolean heldNow = false;
        if (ownedByBuyer && listing.state() == ListingState.PENDING_PAYMENT
                && ListingDao.hold(conn, listing.id(), buyer)) {
            TransactionDao.log(conn, "HOLD", buyer, listing.sellerUuid(), listing.id(), entry.amount(), refundOwed
                    ? "REFUND OWED " + reason + ", attempt " + entry.id() + " - repay buyer by hand"
                    : "UNCERTAIN " + reason + ", attempt " + entry.id() + " - verify buyer balance");
            held = true;
            heldNow = true;
        }
        String listingStatus = held ? "Listing held as PAYMENT_HELD"
                : "Listing is " + listing.state() + " and could not be held";
        plugin.getLogger().severe(refundOwed
                ? "REFUND OWED: market purchase " + listing.id() + " by " + buyer + " for " + entry.amount()
                        + " coppets (attempt " + entry.id() + "): " + reason + ". " + listingStatus
                        + "; journal entry kept. The buyer paid, no goods were delivered and the refund was refused,"
                        + " so the buyer is owed " + entry.amount() + " coppets. The refund will not be retried"
                        + " automatically; repay the buyer by hand."
                : "UNCERTAIN: market purchase " + listing.id() + " by " + buyer + " for " + entry.amount()
                        + " coppets (attempt " + entry.id() + "): " + reason + ". " + listingStatus
                        + "; journal entry kept. No goods delivered, no refund confirmed and no further refund"
                        + " attempted; check the buyer's balance and settle by hand.");
        if (!heldNow) return Repair.KEEP;
        UUID listingId = listing.id();
        long amount = entry.amount();
        return new Repair(false, () -> MysterriaAuditBridge.emit(refundOwed
                        ? "agora.purchase.recovery_refunded" : "agora.purchase.recovery_unproven", false,
                buyer, buyer, listingId, identity,
                (refundOwed ? "refund owed: " : "payment unproven: ") + reason + "; listing held",
                MysterriaAuditBridge.moneyMetadata(0, Map.of("listing_id", listingId.toString(),
                        "attempted_amount", amount))));
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
}
