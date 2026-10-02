package dev.ua.ikeepcalm.wiic.domain.agora.market.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.LedgerDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.LedgerEntry;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.CourierService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.JournalRecovery;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.Listing;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ListingState;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.SaleNotifier;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.utils.MysterriaAuditBridge;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.Map;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The player-to-player purchase pipeline. Modeled on {@code PurchaseService}
 * (single-flight lock, async Vault leg, main-thread callback) with one extra
 * moving part: a CAS reservation on the listing row so two buyers can never both
 * pay for the same item.
 *
 * <p>Stages: reserve (ACTIVE → PENDING_PAYMENT, price ceiling + self-buy enforced
 * in the same statement) → journal BUY intent → async Vault withdraw → journal
 * BUY_PAID proof → one transaction {SOLD + buyer stash row + seller ledger row
 * (net = gross − tax) + audit} → journal remove. A failed withdraw releases the
 * reservation; a crash between withdraw and commit is completed by
 * {@link JournalRecovery} at startup.
 *
 * <p>The intent/proof pair exists because no ordering can make an external Vault
 * withdraw atomic with a local commit. Writing the intent first guarantees every
 * debited buyer leaves a record; requiring the proof before goods change hands
 * guarantees the ambiguous middle is unwound rather than guessed at. The failure
 * direction is always "no sale", never "free goods".
 *
 * <p>When the economy provider throws, the money outcome is unknown. The listing then
 * moves to {@code PAYMENT_HELD} and the journal entry stays on disk: no goods are
 * delivered, no refund is attempted, and nothing can resell the item until staff settle the
 * attempt. Refunds write a {@code BUY_REFUND} marker before the deposit, so recovery can
 * never refund an attempt twice or complete it as a sale after a possible refund. A refund
 * the provider refuses is a known debt, not a settled refund: it gains a
 * {@code BUY_REFUND_REFUSED} marker and is held the same way until staff repay the buyer.
 */
public class MarketPurchaseService {

    public enum Result {
        SUCCESS, ALREADY_IN_PROGRESS, NO_LONGER_AVAILABLE, PRICE_CHANGED,
        SELF_PURCHASE, INSUFFICIENT_FUNDS, ERROR,
        /** The payment or refund outcome is unknown; the goods are held for staff. */
        UNCERTAIN
    }

    /** {@code couriered} is true when a postman took the goods instead of the stash. */
    public record Outcome(Result result, long price, boolean couriered) {
        static Outcome of(Result result) {
            return new Outcome(result, 0, false);
        }
    }

    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final dev.ua.ikeepcalm.wiic.utils.AuditSampler IN_PROGRESS_AUDIT =
            new dev.ua.ikeepcalm.wiic.utils.AuditSampler();

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final MarketJournal journal;
    /** Null when undead-postmans is absent — every purchase then goes to the stash. */
    private final @Nullable CourierService courier;
    private final SaleNotifier notifier;

    public MarketPurchaseService(WIIC plugin, MarketConfig config, MarketDatabase db, MarketJournal journal,
                                @Nullable CourierService courier, SaleNotifier notifier) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.journal = journal;
        this.courier = courier;
        this.notifier = notifier;
    }

    /**
     * Buys the listing. {@code quotedPrice} is what the detail GUI showed — the
     * buyer is never charged more (the reservation CAS enforces it).
     */
    public void purchase(Player buyer, UUID listingId, long quotedPrice, Consumer<Outcome> callback) {
        UUID uuid = buyer.getUniqueId();
        String attemptId = UUID.randomUUID().toString();
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("agora-purchase", attemptId);
        if (!IN_FLIGHT.add(uuid)) {
            if (shouldAuditInProgress(uuid)) {
                MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, null, listingId, identity,
                        "already in progress", MysterriaAuditBridge.moneyMetadata(0,
                                Map.of("listing_id", listingId.toString(), "quoted_price", quotedPrice)));
            }
            callback.accept(Outcome.of(Result.ALREADY_IN_PROGRESS));
            return;
        }

        long now = System.currentTimeMillis();
        // Identifies this attempt. A listing can be attempted more than once over its life
        // (a reservation the sweeper released, then a real sale by someone else), so keying
        // the journal on the listing would let one attempt erase the other's proof of payment.
        db.transactionThenMain(conn -> {
            if (ListingDao.reserve(conn, listingId, uuid, quotedPrice, now)) {
                return ListingDao.findById(conn, listingId);
            }
            // Reservation failed — read the row to report why.
            Listing listing = ListingDao.findById(conn, listingId);
            if (listing == null || listing.state() != ListingState.ACTIVE) throw new PurchaseAbort(Result.NO_LONGER_AVAILABLE);
            if (listing.sellerUuid().equals(uuid)) throw new PurchaseAbort(Result.SELF_PURCHASE);
            throw new PurchaseAbort(Result.PRICE_CHANGED);
        }, listing -> {
            if (listing == null) {
                MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, null, listingId, identity,
                        "listing unavailable", MysterriaAuditBridge.moneyMetadata(0,
                                Map.of("listing_id", listingId.toString(), "quoted_price", quotedPrice)));
                finish(uuid, callback, Outcome.of(Result.NO_LONGER_AVAILABLE));
                return;
            }
            withdrawAndCommit(buyer, uuid, listing, attemptId, identity, callback);
        }, error -> {
            Result result = error instanceof PurchaseAbort abort ? abort.result : Result.ERROR;
            if (result == Result.ERROR) plugin.getLogger().severe("Market reserve failed: " + error);
            MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, null, listingId, identity,
                    result.name().toLowerCase(), MysterriaAuditBridge.moneyMetadata(0,
                            Map.of("listing_id", listingId.toString(), "quoted_price", quotedPrice)));
            finish(uuid, callback, Outcome.of(result));
        });
    }

    private void withdrawAndCommit(Player buyer, UUID uuid, Listing listing, String attemptId,
                                   MysterriaAuditBridge.AuditIdentity identity,
                                   Consumer<Outcome> callback) {
        // Validate old escrow before any money moves. Keep its bytes for staff review.
        try {
            if (ItemInspector.containsTemporaryItem(ItemStack.deserializeBytes(listing.itemBytes()))) {
                plugin.getLogger().warning("Blocked temporary-item purchase: listing " + listing.id());
                MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, listing.sellerUuid(), listing.id(),
                        identity, "temporary item blocked", MysterriaAuditBridge.moneyMetadata(0,
                                MysterriaAuditBridge.metadata(Map.of("listing_id", listing.id().toString()),
                                        MysterriaAuditBridge.itemMetadata(listing.itemBytes()))));
                releaseThen(listing, uuid, () -> finish(uuid, callback, Outcome.of(Result.NO_LONGER_AVAILABLE)));
                return;
            }
        } catch (RuntimeException invalidItem) {
            plugin.getLogger().warning("Unreadable listing " + listing.id() + ": " + invalidItem.getMessage());
            MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, listing.sellerUuid(), listing.id(),
                    identity, "listing item unreadable", MysterriaAuditBridge.moneyMetadata(0,
                            Map.of("listing_id", listing.id().toString())));
            releaseThen(listing, uuid, () -> finish(uuid, callback, Outcome.of(Result.ERROR)));
            return;
        }
        long price = listing.price();

        // Intent goes to disk before the money moves. If the server dies in the window
        // around the withdraw, recovery finds this entry and can at least account for the
        // attempt; without it, a crash mid-withdraw leaves a debited buyer and no record
        // anywhere that it ever happened.
        try {
            journal.append(MarketJournal.Type.BUY, attemptId, uuid, price, null, listing.id().toString());
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("Market journal unavailable, refusing purchase: " + e.getMessage());
            MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, listing.sellerUuid(), listing.id(), identity,
                    "purchase journal unavailable", MysterriaAuditBridge.moneyMetadata(0,
                            MysterriaAuditBridge.metadata(Map.of("listing_id", listing.id().toString()),
                                    MysterriaAuditBridge.itemMetadata(listing.itemBytes()))));
            releaseThen(listing, uuid, () -> finish(uuid, callback, Outcome.of(Result.ERROR)));
            return;
        }

        // Entity position may only be read on the main thread; the async rows below reuse it.
        Map<String, Object> location = MysterriaAuditBridge.playerLocation(buyer);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BigDecimal balanceBefore = balance(uuid);
            VaultUtil.Payment payment = VaultUtil.withdrawChecked(uuid, price,
                    "market purchase of listing " + listing.id());
            if (payment == VaultUtil.Payment.INDETERMINATE) {
                // The buyer may or may not have paid. Releasing would let someone else buy
                // goods this buyer may own; refunding or delivering would invent value. The
                // BUY intent stays on disk and the listing is held for staff.
                TransactionLogger.logNote(buyer, "MARKET BUY withdraw of " + price + " coppets UNCERTAIN for listing "
                        + listing.id() + " (attempt " + attemptId + "), goods held for manual reconciliation");
                MysterriaAuditBridge.emitPaymentIndeterminate(
                        "agora.purchase", uuid, listing.sellerUuid(), listing.id(), identity, price,
                        balanceBefore, balance(uuid), MysterriaAuditBridge.metadata(
                                Map.of("listing_id", listing.id().toString()), location));
                holdThen(listing, uuid, attemptId, false, "withdraw outcome unknown",
                        () -> finish(uuid, callback, Outcome.of(Result.UNCERTAIN)));
                return;
            }
            if (!payment.succeeded()) {
                TransactionLogger.logNote(buyer, "MARKET BUY withdraw of " + price + " coppets failed for listing " + listing.id());
                MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, listing.sellerUuid(), listing.id(), identity,
                        withdrawFailureReason(balanceBefore, price),
                        MysterriaAuditBridge.moneyMetadata(0, balanceBefore, balance(uuid),
                                MysterriaAuditBridge.metadata(MysterriaAuditBridge.metadata(
                                        Map.of("listing_id", listing.id().toString()), location),
                                        MysterriaAuditBridge.itemMetadata(listing.itemBytes()))));
                journal.remove(attemptId);
                releaseThen(listing, uuid, () -> finish(uuid, callback, Outcome.of(Result.INSUFFICIENT_FUNDS)));
                return;
            }
            BigDecimal balanceAfterCharge = balance(uuid);

            // Proof the money moved. Recovery refuses to hand over goods without it, so a
            // marker that cannot be written has to unwind the purchase here and now —
            // continuing would leave a paid-for sale that recovery would later treat as
            // unproven and hold for staff instead of completing.
            try {
                journal.append(MarketJournal.Type.BUY_PAID, attemptId, uuid, price, null, listing.id().toString());
            } catch (IllegalStateException e) {
                plugin.getLogger().severe("Market journal marker write failed after withdraw, refunding: " + e.getMessage());
                MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, listing.sellerUuid(), listing.id(), identity,
                        "payment proof write failed", MysterriaAuditBridge.moneyMetadata(-price,
                                balanceBefore, balanceAfterCharge, MysterriaAuditBridge.metadata(
                                        MysterriaAuditBridge.metadata(
                                                Map.of("listing_id", listing.id().toString()), location),
                                        MysterriaAuditBridge.itemMetadata(listing.itemBytes()))));
                // No paid proof reached disk, so recovery already treats this attempt as
                // unproven and will neither sell nor refund it.
                refundThenSettle(buyer, uuid, listing, attemptId, "journal marker failed", identity, location, callback);
                return;
            }

            commitSale(buyer, uuid, listing, attemptId, identity, balanceBefore, balanceAfterCharge, callback);
        });
    }

    /** Hands the listing back to the market, then runs {@code then} on the main thread. */
    private void releaseThen(Listing listing, UUID uuid, Runnable then) {
        db.transactionThenMain(conn -> {
            ListingDao.releaseReservation(conn, listing.id(), uuid);
            return null;
        }, ignored -> then.run(), error -> {
            plugin.getLogger().severe("Failed to release reservation " + listing.id() + ": " + error
                    + " (sweeper will release it)");
            then.run();
        });
    }

    /**
     * Moves the reservation to {@code PAYMENT_HELD} for an attempt whose money outcome is
     * unknown, or whose refund was refused ({@code refundOwed}), then runs {@code then} on the
     * main thread. The journal entry is deliberately kept: with the held row it is the
     * reconciliation record, and while it survives neither the sweeper nor startup recovery
     * will release the listing.
     */
    private void holdThen(Listing listing, UUID uuid, String attemptId, boolean refundOwed, String reason,
                          Runnable then) {
        String diagnosis = refundOwed
                ? "REFUND OWED: market purchase " + listing.id() + " by " + uuid + " for " + listing.price()
                        + " coppets (attempt " + attemptId + "): " + reason + ". The buyer paid, no goods were"
                        + " delivered and the refund was refused, so the buyer is owed " + listing.price()
                        + " coppets. The refund will not be retried automatically; repay the buyer by hand."
                : "UNCERTAIN: market purchase " + listing.id() + " by " + uuid + " for " + listing.price()
                        + " coppets (attempt " + attemptId + "): " + reason + ". No goods delivered, no refund"
                        + " confirmed and no further refund attempted; check the buyer's balance and settle by hand.";
        String note = refundOwed
                ? "REFUND OWED " + reason + ", attempt " + attemptId + " - repay buyer by hand"
                : "UNCERTAIN " + reason + ", attempt " + attemptId + " - verify buyer balance";
        db.transactionThenMain(conn -> {
            boolean held = ListingDao.hold(conn, listing.id(), uuid);
            TransactionDao.log(conn, "HOLD", uuid, listing.sellerUuid(), listing.id(), listing.price(), note);
            return held;
        }, held -> {
            plugin.getLogger().severe(diagnosis + (held
                    ? " Listing held as PAYMENT_HELD and journal entry kept."
                    : " Listing was no longer reserved by this buyer and could not be held; journal entry kept."));
            then.run();
        }, error -> {
            plugin.getLogger().severe(diagnosis + " Hold could not be written (" + error + "); the journal entry"
                    + " is kept, so the sweeper will not release the listing and startup recovery will hold it.");
            then.run();
        });
    }

    private void commitSale(Player buyer, UUID uuid, Listing listing, String attemptId,
                            MysterriaAuditBridge.AuditIdentity identity, BigDecimal balanceBefore,
                            BigDecimal balanceAfterCharge,
                            Consumer<Outcome> callback) {
        long price = listing.price();
        long tax = config.saleTax(price);
        long net = price - tax;
        long now = System.currentTimeMillis();
        // Fixed up front so the courier hand-off can claim exactly this row after the commit.
        UUID stashId = UUID.randomUUID();

        db.transactionThenMain(conn -> {
            if (!ListingDao.markSold(conn, listing.id(), uuid, now)) {
                throw new IllegalStateException("Listing " + listing.id() + " left PENDING_PAYMENT unexpectedly");
            }
            StashDao.insert(conn, new StashItem(stashId, uuid, listing.itemBytes(),
                    listing.material(), listing.amount(), listing.displayName(),
                    StashItem.SOURCE_PURCHASE, listing.id().toString(), now));
            LedgerDao.insert(conn, new LedgerEntry(UUID.randomUUID(), listing.sellerUuid(),
                    price, tax, net, listing.id(), now));
            TransactionDao.log(conn, "BUY", uuid, listing.sellerUuid(), listing.id(), price, null);
            if (tax > 0) TransactionDao.log(conn, "TAX", listing.sellerUuid(), null, listing.id(), tax, "sink");
            return null;
        }, done -> {
            journal.remove(attemptId);
            TransactionLogger.logNote(buyer, "MARKET BUY " + listing.material().name() + " x" + listing.amount()
                    + " for " + price + " coppets from " + listing.sellerName() + " (listing " + listing.id() + ")");
            MysterriaAuditBridge.emit("agora.purchase.completed", true, uuid, listing.sellerUuid(), listing.id(), identity,
                    "listing purchase committed", MysterriaAuditBridge.moneyMetadata(-price,
                            balanceBefore, balanceAfterCharge, MysterriaAuditBridge.metadata(
                                    Map.of("listing_id", listing.id().toString(), "tax", tax, "net", net),
                                    MysterriaAuditBridge.itemMetadata(listing.itemBytes()))));
            Player seller = Bukkit.getPlayer(listing.sellerUuid());
            if (seller != null) {
                TransactionLogger.logNote(seller, "MARKET SOLD " + listing.material().name() + " x" + listing.amount()
                        + " -> ledger +" + net + " coppets (tax " + tax + ")");
            }
            // Word reaches the seller wherever they are — that something sold, never what
            // or for how much. Counting it is the Ledger Keeper's job, in person.
            notifier.sold(listing.sellerUuid());
            // The sale is already final; courier delivery only decides where the goods wait.
            if (courier != null && courier.hasContract(uuid)) {
                courier.tryDeliver(buyer, stashId, listing.itemBytes(), listing.sellerUuid(), listing.sellerName(),
                        identity, couriered -> finish(uuid, callback, new Outcome(Result.SUCCESS, price, couriered)));
            } else {
                finish(uuid, callback, new Outcome(Result.SUCCESS, price, false));
            }
        }, error -> {
            // Money was taken but the sale did not commit. The journal entry stays on
            // disk so startup recovery can finish the sale if this was a crash; for a
            // plain SQL failure we refund immediately and release the reservation.
            plugin.getLogger().severe("Market sale commit failed for listing " + listing.id() + ": " + error);
            MysterriaAuditBridge.emit("agora.purchase.failed", false, uuid, listing.sellerUuid(), listing.id(), identity,
                    "sale commit failed", MysterriaAuditBridge.moneyMetadata(-price,
                            balanceBefore, balanceAfterCharge, MysterriaAuditBridge.metadata(
                                    Map.of("listing_id", listing.id().toString()),
                                    MysterriaAuditBridge.itemMetadata(listing.itemBytes()))));
            Map<String, Object> location = MysterriaAuditBridge.playerLocation(buyer);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    refundThenSettle(buyer, uuid, listing, attemptId, "sale commit failed", identity, location,
                            callback));
        });
    }

    /**
     * Refunds an abandoned purchase and settles the listing by what the refund proved.
     * Runs off the main thread, before the reservation is released. {@code location} is
     * captured on the main thread for the refund audit row.
     *
     * <p>The {@code BUY_REFUND} marker goes to disk before the deposit. From then on recovery
     * can neither refund the attempt again nor complete it as a sale, so a crash or provider
     * throw around the deposit leaves at most one refund and no goods.
     */
    private void refundThenSettle(Player buyer, UUID uuid, Listing listing, String attemptId, String reason,
                                  MysterriaAuditBridge.AuditIdentity identity, Map<String, Object> location,
                                  Consumer<Outcome> callback) {
        long price = listing.price();
        try {
            journal.append(MarketJournal.Type.BUY_REFUND, attemptId, uuid, price, null, listing.id().toString());
        } catch (IllegalStateException e) {
            if (journal.contains(MarketJournal.Type.BUY_PAID, attemptId)) {
                // Paid proof is on disk with nothing to say a refund was tried. Refunding now
                // could let recovery also complete the sale. Leave the reservation and the
                // journal entry as they are: the sweeper keeps the hold and startup recovery
                // completes the paid sale exactly once.
                plugin.getLogger().severe("Refund marker write failed for paid purchase " + listing.id() + " by " + uuid
                        + " (attempt " + attemptId + "): " + e.getMessage() + ". No refund issued; the sale is"
                        + " left reserved for startup recovery to complete.");
                Bukkit.getScheduler().runTask(plugin, () -> finish(uuid, callback, Outcome.of(Result.ERROR)));
                return;
            }
            // No paid proof on disk: recovery already treats this attempt as unproven and will
            // neither sell nor refund it, so the refund below cannot be repeated.
        }

        BigDecimal balanceBefore = balance(uuid);
        VaultUtil.Payment refund = VaultUtil.depositChecked(uuid, price, "market purchase refund (" + reason + ")");
        boolean refunded = refund.succeeded();
        MysterriaAuditBridge.emit("agora.purchase.refunded", refunded, uuid, uuid, listing.id(), identity,
                reason, MysterriaAuditBridge.moneyMetadata(refunded ? price : 0,
                        balanceBefore, balance(uuid), MysterriaAuditBridge.metadata(
                                Map.of("listing_id", listing.id().toString(), "payment", refund.name()), location)));
        if (refund == VaultUtil.Payment.INDETERMINATE) {
            // The buyer paid and may or may not have the money back. Neither a second refund
            // nor the sale is safe; hold the goods with the journal entry for staff.
            TransactionLogger.logNote(buyer, "MARKET BUY refund of " + price + " coppets (" + reason + ") UNCERTAIN"
                    + " for listing " + listing.id() + " (attempt " + attemptId + "), manual reconciliation needed");
            holdThen(listing, uuid, attemptId, false, "refund outcome unknown (" + reason + ")",
                    () -> finish(uuid, callback, Outcome.of(Result.UNCERTAIN)));
            return;
        }
        TransactionLogger.logNote(buyer, "MARKET BUY refund of " + price + " coppets (" + reason + ") "
                + (refunded ? "OK" : "FAILED"));
        if (!refunded) {
            // The buyer paid and got neither goods nor money back. That is a known debt, not a
            // settled refund: mark it on disk, keep the entry and hold the goods for staff. The
            // deposit is never repeated automatically; staff repay the buyer by hand.
            plugin.getLogger().severe("Failed to refund " + price + " coppets to " + uuid);
            try {
                journal.append(MarketJournal.Type.BUY_REFUND_REFUSED, attemptId, uuid, price, null,
                        listing.id().toString());
            } catch (IllegalStateException e) {
                plugin.getLogger().severe("Purchase " + listing.id() + " by " + uuid + " (attempt " + attemptId
                        + ") had its refund refused, but the refused marker could not be written: " + e.getMessage()
                        + ". The journal entry is kept, so startup recovery will report the refund as uncertain;"
                        + " this log line is the outcome: " + price + " coppets are owed.");
            }
            holdThen(listing, uuid, attemptId, true, "refund refused (" + reason + ")",
                    () -> finish(uuid, callback, Outcome.of(Result.ERROR)));
            return;
        }
        // The refund landed, so the attempt is done. Left behind, the entry would make startup
        // recovery report a settled refund as uncertain.
        if (!journal.remove(attemptId)) {
            plugin.getLogger().severe("Purchase " + listing.id() + " by " + uuid + " (attempt " + attemptId
                    + ") had its refund completed, but its journal entry could not be removed. Startup recovery"
                    + " will report it as uncertain; this log line is the outcome.");
        }
        releaseThen(listing, uuid, () -> finish(uuid, callback, Outcome.of(Result.ERROR)));
    }

    private static BigDecimal balance(UUID uuid) {
        return VaultUtil.balance(uuid);
    }

    /** Only an observed balance below the price proves the refusal was about funds. */
    static String withdrawFailureReason(@Nullable BigDecimal balanceBefore, long price) {
        return balanceBefore != null && balanceBefore.compareTo(BigDecimal.valueOf(price)) < 0
                ? "insufficient_funds" : "withdraw_failed";
    }

    /** Samples double-click rejections to one row per player per window. */
    private static boolean shouldAuditInProgress(UUID uuid) {
        return IN_PROGRESS_AUDIT.shouldEmit(uuid);
    }

    private void finish(UUID uuid, Consumer<Outcome> callback, Outcome outcome) {
        IN_FLIGHT.remove(uuid);
        callback.accept(outcome);
    }

    private static final class PurchaseAbort extends MarketDatabase.ControlFlow {
        final Result result;

        PurchaseAbort(Result result) {
            super(result.name());
            this.result = result;
        }
    }

    /** Drops every single-flight guard. Called on module shutdown — these sets are
     *  static and would otherwise carry a stale lock across a plugin reload. */
    public static void releaseAll() {
        IN_FLIGHT.clear();
        IN_PROGRESS_AUDIT.clear();
    }

}
