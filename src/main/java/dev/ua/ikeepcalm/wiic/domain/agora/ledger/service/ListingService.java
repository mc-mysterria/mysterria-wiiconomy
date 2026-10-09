package dev.ua.ikeepcalm.wiic.domain.agora.ledger.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.DailyCounterDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.ItemSnapshot;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.Listing;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ListingState;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.JournalRecovery;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.utils.AuditSampler;
import dev.ua.ikeepcalm.wiic.utils.MysterriaAuditBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Creates and cancels broker listings. Mirrors {@code PurchaseService}'s pipeline:
 * per-player single-flight lock, main-thread validation, async money leg, DB
 * transaction, refund toward the player on failure.
 *
 * <p>Listing-creation ordering: the item comes <b>first</b>. By the time this class is
 * called the goods are already out of the seller's inventory and exist only as a local
 * variable, so they are journaled before anything else — before the limit check, before
 * the fee, before the insert. Only then is the fee withdrawn and the listing committed.
 * A crash anywhere after the journal write is repaired at startup by
 * {@link JournalRecovery}: the item lands in the seller's stash, never lost, never
 * duplicated. A failure hands the item back through the GUI instead, unless the journal
 * could not be pruned — see {@link Outcome#itemRetained()}, which is what keeps "given
 * back" and "recovered into the stash" from both happening to the same item.
 */
public class ListingService {

    public enum Result {
        SUCCESS, ALREADY_IN_PROGRESS, ITEM_DENIED, PRICE_OUT_OF_BOUNDS,
        DAILY_LIMIT, MAX_ACTIVE, INSUFFICIENT_FEE, ERROR,
        /** The fee withdraw outcome is unknown; nothing was listed and no refund is issued. */
        UNCERTAIN
    }

    /**
     * @param itemRetained set when the listing failed but the journal still holds the item.
     *                     The caller must <b>not</b> hand it back — startup recovery will
     *                     deliver it to the seller's stash, and doing both would duplicate it.
     */
    public record Outcome(Result result, String denyMessageKey, long fee, boolean itemRetained) {
        public static Outcome of(Result result) {
            return new Outcome(result, null, 0, false);
        }

        /** A failure whose disposition of the item depends on whether the journal let go of it. */
        static Outcome failed(Result result, long fee, boolean journalPruned) {
            return new Outcome(result, null, fee, !journalPruned);
        }
    }

    private static final AuditSampler REJECTION_AUDIT =
            new AuditSampler();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final MarketJournal journal;
    private final ItemInspector inspector;

    public ListingService(WIIC plugin, MarketConfig config, MarketDatabase db,
                          MarketJournal journal, ItemInspector inspector) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.journal = journal;
        this.inspector = inspector;
    }

    /**
     * Lists {@code item} for {@code price} coppets. The item must already be out of
     * the player's inventory (held by the ListItemGUI's virtual inventory). On any
     * non-SUCCESS outcome the caller must hand the item back to the player.
     *
     * @param plotId storefront attribution for prestige-plot renters, or null.
     * @param callback main-thread outcome consumer.
     */
    public void createListing(Player seller, ItemStack item, long price, String plotId, Consumer<Outcome> callback) {
        UUID uuid = seller.getUniqueId();
        UUID listingId = UUID.randomUUID();
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("agora-listing", listingId);
        if (!IN_FLIGHT.add(uuid)) {
            emitRejected(uuid, listingId, identity, item, price, "already in progress");
            callback.accept(Outcome.of(Result.ALREADY_IN_PROGRESS));
            return;
        }

        String denied = inspector.checkDenied(item);
        if (denied != null) {
            emitRejected(uuid, listingId, identity, item, price, denied);
            finish(uuid, callback, new Outcome(Result.ITEM_DENIED, denied, 0, false));
            return;
        }
        if (price < config.minPrice() || price > config.maxPrice()) {
            emitRejected(uuid, listingId, identity, item, price, "price out of bounds");
            finish(uuid, callback, Outcome.of(Result.PRICE_OUT_OF_BOUNDS));
            return;
        }

        long fee = config.listingFee(price);
        ItemSnapshot snapshot = inspector.snapshot(item);
        byte[] bytes = item.serializeAsBytes();
        long now = System.currentTimeMillis();
        Listing listing = new Listing(listingId, uuid, seller.getName(), bytes,
                snapshot.material(), snapshot.amount(), snapshot.displayName(), snapshot.category(),
                snapshot.coiItem(), snapshot.coiPathway(), snapshot.coiSequence(),
                price, ListingState.ACTIVE, null, plotId, now, now + config.listingDurationMs(),
                snapshot.valueKey());

        // The item is already out of the seller's inventory by the time we are called, so it
        // exists nowhere but this method's local variable until the journal has it. Write
        // that first — before the fee, before the insert — so no crash can strand it.
        try {
            journal.append(MarketJournal.Type.LIST, listingId.toString(), uuid, price, bytes);
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("Market journal unavailable, aborting listing: " + e.getMessage());
            finish(uuid, callback, Outcome.of(Result.ERROR));
            return;
        }

        // Limits are re-checked authoritatively inside the insert transaction; this earlier
        // read only spares a seller who is over their limit the charge-then-refund dance.
        db.submitThenMain(conn -> {
            if (DailyCounterDao.listingsCreatedToday(conn, uuid) >= config.dailyListingLimit()) return Result.DAILY_LIMIT;
            if (ListingDao.countActiveBySeller(conn, uuid) >= config.maxActivePerPlayer()) return Result.MAX_ACTIVE;
            return null;
        }, preflight -> {
            if (preflight != null) {
                emitRejected(uuid, listingId, identity, item, price, preflight.name().toLowerCase());
                finish(uuid, callback, Outcome.failed(preflight, 0, journal.remove(listingId.toString())));
                return;
            }
            chargeAndInsert(seller, uuid, listing, listingId, identity, snapshot,
                    price, fee, callback);
        }, error -> {
            plugin.getLogger().severe("Market listing preflight failed for " + seller.getName() + ": " + error);
            emitFailed(uuid, listingId, identity, item, price, "listing preflight failed");
            finish(uuid, callback, Outcome.failed(Result.ERROR, 0, journal.remove(listingId.toString())));
        });
    }

    private void chargeAndInsert(Player seller, UUID uuid, Listing listing, UUID listingId,
                                 MysterriaAuditBridge.AuditIdentity identity,
                                 ItemSnapshot snapshot,
                                 long price, long fee,
                                 Consumer<Outcome> callback) {
        // Called from the preflight's main-thread continuation. Entity position may only be
        // read on the main thread; the async rows below reuse it.
        Map<String, Object> location = MysterriaAuditBridge.playerLocation(seller);
        // Fee is a sink (never deposited anywhere), see market.yml.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // The serialized item is parsed here, off the main thread.
            Map<String, Object> itemAuditMetadata = MysterriaAuditBridge.itemMetadata(listing.itemBytes());
            BigDecimal balanceBefore = VaultUtil.balance(uuid);
            VaultUtil.Payment payment = fee > 0
                    ? VaultUtil.withdrawChecked(uuid, fee, "market listing fee for " + listingId)
                    : VaultUtil.Payment.SUCCESS;
            if (!payment.succeeded()) {
                // Indeterminate: the fee may be gone, but no refund is safe until it is proven.
                // Nothing is listed either way, so the seller's own goods still go back.
                boolean uncertain = payment == VaultUtil.Payment.INDETERMINATE;
                TransactionLogger.logNote(seller, "MARKET LIST fee withdraw of " + fee + " coppets "
                        + (uncertain ? "UNCERTAIN for listing " + listingId + ", manual reconciliation needed" : "failed"));
                boolean pruned = journal.remove(listingId.toString());
                Result result = uncertain ? Result.UNCERTAIN : Result.INSUFFICIENT_FEE;
                Bukkit.getScheduler().runTask(plugin, () ->
                        finish(uuid, callback, Outcome.failed(result, fee, pruned)));
                if (uncertain) MysterriaAuditBridge.emitPaymentIndeterminate(
                        "agora.listing.fee", uuid, uuid, listingId, identity, fee, balanceBefore, VaultUtil.balance(uuid),
                        MysterriaAuditBridge.metadata(Map.of("price", price, "fee", fee), location));
                else MysterriaAuditBridge.emit("agora.listing.failed", false, uuid, uuid, listingId, identity,
                        "listing fee withdrawal failed", MysterriaAuditBridge.moneyMetadata(0,
                                balanceBefore, VaultUtil.balance(uuid), MysterriaAuditBridge.metadata(MysterriaAuditBridge.metadata(
                                        Map.of("price", price, "fee", fee), location), itemAuditMetadata)));
                return;
            }
            BigDecimal balanceAfterCharge = VaultUtil.balance(uuid);

            db.transactionThenMain(conn -> {
                if (!DailyCounterDao.incrementIfBelow(conn, uuid, config.dailyListingLimit())) {
                    throw new ListingLimitException(Result.DAILY_LIMIT);
                }
                if (ListingDao.countActiveBySeller(conn, uuid) >= config.maxActivePerPlayer()) {
                    throw new ListingLimitException(Result.MAX_ACTIVE);
                }
                ListingDao.insert(conn, listing);
                TransactionDao.log(conn, "LIST", uuid, null, listingId, price, snapshot.material().name() + " x" + snapshot.amount());
                if (fee > 0) TransactionDao.log(conn, "LIST_FEE", uuid, null, listingId, fee, "sink");
                return null;
            }, ignored -> {
                journal.remove(listingId.toString());
                TransactionLogger.logNote(seller, "MARKET LIST " + snapshot.material().name() + " x" + snapshot.amount()
                        + " for " + price + " coppets (fee " + fee + ") id=" + listingId);
                MysterriaAuditBridge.emit("agora.listing.created", true, uuid, uuid, listingId, identity,
                        "listing committed", MysterriaAuditBridge.moneyMetadata(-fee,
                                balanceBefore, balanceAfterCharge, MysterriaAuditBridge.metadata(
                                        Map.of("price", price, "fee", fee, "plot_id",
                                                listing.plotId() == null ? "" : listing.plotId()),
                                        itemAuditMetadata)));
                finish(uuid, callback, new Outcome(Result.SUCCESS, null, fee, false));
            }, error -> {
                boolean pruned = journal.remove(listingId.toString());
                refundFee(seller, uuid, listingId, fee, "listing insert failed", identity, location);
                Result result = error instanceof ListingLimitException limit ? limit.result : Result.ERROR;
                if (result == Result.ERROR) {
                    plugin.getLogger().severe("Market listing insert failed for " + seller.getName() + ": " + error);
                }
                MysterriaAuditBridge.emit("agora.listing.failed", false, uuid, uuid, listingId, identity,
                        result.name().toLowerCase(), MysterriaAuditBridge.moneyMetadata(-fee,
                                balanceBefore, balanceAfterCharge, Map.of("price", price, "fee", fee)));
                finish(uuid, callback, Outcome.failed(result, fee, pruned));
            });
        });
    }

    /** Cancels the seller's own ACTIVE listing; the item moves to their stash in the same transaction. */
    public void cancelListing(Player seller, UUID listingId, Consumer<Boolean> callback) {
        UUID uuid = seller.getUniqueId();
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("agora-listing", listingId);
        if (!IN_FLIGHT.add(uuid)) {
            callback.accept(false);
            return;
        }
        db.transactionThenMain(conn -> {
            Listing listing = ListingDao.findById(conn, listingId);
            if (listing == null || !ListingDao.cancel(conn, listingId, uuid)) return false;
            StashDao.insert(conn, new StashItem(UUID.randomUUID(), uuid, listing.itemBytes(),
                    listing.material(), listing.amount(), listing.displayName(),
                    StashItem.SOURCE_CANCELLED, listingId.toString(), System.currentTimeMillis()));
            TransactionDao.log(conn, "CANCEL", uuid, null, listingId, listing.price(), null);
            return true;
        }, success -> {
            IN_FLIGHT.remove(uuid);
            if (success) {
                TransactionLogger.logNote(seller, "MARKET CANCEL listing " + listingId + " -> stash");
                MysterriaAuditBridge.emit("agora.listing.cancelled", true, uuid, uuid, listingId, identity,
                        "listing cancelled", Map.of());
            }
            callback.accept(success);
        }, error -> {
            IN_FLIGHT.remove(uuid);
            plugin.getLogger().severe("Market cancel failed for " + seller.getName() + ": " + error);
            MysterriaAuditBridge.emit("agora.listing.failed", false, uuid, uuid, listingId, identity,
                    "listing cancel failed: database error", Map.of("operation", "cancel",
                            "listing_id", listingId.toString(), "error", String.valueOf(error.getClass().getSimpleName())));
            callback.accept(false);
        });
    }

    /** {@code location} is captured on the main thread; the refund row is emitted async. */
    private void refundFee(Player seller, UUID uuid, UUID listingId, long fee, String reason,
                           MysterriaAuditBridge.AuditIdentity identity, Map<String, Object> location) {
        if (fee <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BigDecimal balanceBefore = VaultUtil.balance(uuid);
            VaultUtil.Payment payment = VaultUtil.depositChecked(uuid, fee,
                    "market listing fee refund (" + reason + ")");
            boolean refunded = payment.succeeded();
            if (payment == VaultUtil.Payment.INDETERMINATE) {
                TransactionLogger.logNote(seller, "MARKET LIST fee refund of " + fee + " coppets ("
                        + reason + ") UNCERTAIN, manual reconciliation needed");
                plugin.getLogger().severe("UNCERTAIN: listing fee refund of " + fee + " coppets to " + uuid
                        + " may or may not have landed; check the balance before compensating by hand.");
            } else {
                TransactionLogger.logNote(seller, "MARKET LIST fee refund of " + fee + " coppets ("
                        + reason + ") " + (refunded ? "OK" : "FAILED"));
                if (!refunded) {
                    plugin.getLogger().severe("Failed to refund listing fee of " + fee + " coppets to " + uuid);
                }
            }
            MysterriaAuditBridge.emit("agora.listing.fee_refunded", refunded, uuid, uuid, listingId, identity,
                    reason, MysterriaAuditBridge.moneyMetadata(refunded ? fee : 0,
                            balanceBefore, VaultUtil.balance(uuid), MysterriaAuditBridge.metadata(Map.of("fee", fee,
                                    "listing_id", listingId.toString(), "payment", payment.name()), location)));
        });
    }

    private static void emitRejected(UUID sellerId, UUID listingId,
                                     MysterriaAuditBridge.AuditIdentity identity,
                                     ItemStack item, long price, String reason) {
        // Preflight refusals are click-level: one row per seller and reason per 5 s.
        if (!REJECTION_AUDIT.shouldEmit(List.of(sellerId, reason))) return;
        emitFailed(sellerId, listingId, identity, item, price, reason);
    }

    /** Unsampled listing failure row (real errors, not click-level refusals). */
    private static void emitFailed(UUID sellerId, UUID listingId,
                                   MysterriaAuditBridge.AuditIdentity identity,
                                   ItemStack item, long price, String reason) {
        MysterriaAuditBridge.emit("agora.listing.failed", false, sellerId, sellerId, null, identity,
                reason, MysterriaAuditBridge.moneyMetadata(0,
                        MysterriaAuditBridge.metadata(Map.of("price", price,
                                        "listing_request_id", listingId.toString()),
                                MysterriaAuditBridge.itemMetadata(item))));
    }

    private void finish(UUID uuid, Consumer<Outcome> callback, Outcome outcome) {
        IN_FLIGHT.remove(uuid);
        callback.accept(outcome);
    }

    private static final class ListingLimitException extends MarketDatabase.ControlFlow {
        final Result result;

        ListingLimitException(Result result) {
            super(result.name());
            this.result = result;
        }
    }

    /** Drops every single-flight guard. Called on module shutdown — these sets are
     *  static and would otherwise carry a stale lock across a plugin reload. */
    public static void releaseAll() {
        REJECTION_AUDIT.clear();
        IN_FLIGHT.clear();
    }

}
