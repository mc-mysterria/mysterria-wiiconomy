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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
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
 */
public class MarketPurchaseService {

    public enum Result {
        SUCCESS, ALREADY_IN_PROGRESS, NO_LONGER_AVAILABLE, PRICE_CHANGED,
        SELF_PURCHASE, INSUFFICIENT_FUNDS, ERROR
    }

    /** {@code couriered} is true when a postman took the goods instead of the stash. */
    public record Outcome(Result result, long price, boolean couriered) {
        static Outcome of(Result result) {
            return new Outcome(result, 0, false);
        }
    }

    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

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
        if (!IN_FLIGHT.add(uuid)) {
            callback.accept(Outcome.of(Result.ALREADY_IN_PROGRESS));
            return;
        }

        long now = System.currentTimeMillis();
        // Identifies this attempt. A listing can be attempted more than once over its life
        // (a reservation the sweeper released, then a real sale by someone else), so keying
        // the journal on the listing would let one attempt erase the other's proof of payment.
        String attemptId = UUID.randomUUID().toString();
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
                finish(uuid, callback, Outcome.of(Result.NO_LONGER_AVAILABLE));
                return;
            }
            withdrawAndCommit(buyer, uuid, listing, attemptId, callback);
        }, error -> {
            Result result = error instanceof PurchaseAbort abort ? abort.result : Result.ERROR;
            if (result == Result.ERROR) plugin.getLogger().severe("Market reserve failed: " + error);
            finish(uuid, callback, Outcome.of(result));
        });
    }

    private void withdrawAndCommit(Player buyer, UUID uuid, Listing listing, String attemptId, Consumer<Outcome> callback) {
        long price = listing.price();

        // Intent goes to disk before the money moves. If the server dies in the window
        // around the withdraw, recovery finds this entry and can at least account for the
        // attempt; without it, a crash mid-withdraw leaves a debited buyer and no record
        // anywhere that it ever happened.
        try {
            journal.append(MarketJournal.Type.BUY, attemptId, uuid, price, null, listing.id().toString());
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("Market journal unavailable, refusing purchase: " + e.getMessage());
            releaseThen(listing, uuid, () -> finish(uuid, callback, Outcome.of(Result.ERROR)));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean withdrawn = VaultUtil.withdraw(uuid, price);
            if (!withdrawn) {
                TransactionLogger.logNote(buyer, "MARKET BUY withdraw of " + price + " coppets failed for listing " + listing.id());
                journal.remove(attemptId);
                releaseThen(listing, uuid, () -> finish(uuid, callback, Outcome.of(Result.INSUFFICIENT_FUNDS)));
                return;
            }

            // Proof the money moved. Recovery refuses to hand over goods without it, so a
            // marker that cannot be written has to unwind the purchase here and now —
            // continuing would leave a paid-for sale that recovery would later treat as
            // unpaid and release back onto the market.
            try {
                journal.append(MarketJournal.Type.BUY_PAID, attemptId, uuid, price, null, listing.id().toString());
            } catch (IllegalStateException e) {
                plugin.getLogger().severe("Market journal marker write failed after withdraw, refunding: " + e.getMessage());
                refund(buyer, uuid, price, "journal marker failed");
                // The intent entry has to go with it. Left behind, startup recovery would
                // read it as an unproven purchase and tell staff to check whether the buyer
                // was ever refunded — which they just were, right here.
                if (!journal.remove(attemptId)) {
                    plugin.getLogger().severe("Purchase " + listing.id() + " by " + uuid
                            + " was refunded in full, but its journal entry could not be removed."
                            + " Startup recovery will report it as unproven — it needs no further action.");
                }
                releaseThen(listing, uuid, () -> finish(uuid, callback, Outcome.of(Result.ERROR)));
                return;
            }

            commitSale(buyer, uuid, listing, attemptId, callback);
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

    private void commitSale(Player buyer, UUID uuid, Listing listing, String attemptId, Consumer<Outcome> callback) {
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
                        couriered -> finish(uuid, callback, new Outcome(Result.SUCCESS, price, couriered)));
            } else {
                finish(uuid, callback, new Outcome(Result.SUCCESS, price, false));
            }
        }, error -> {
            // Money was taken but the sale did not commit. The journal entry stays on
            // disk so startup recovery can finish the sale if this was a crash; for a
            // plain SQL failure we refund immediately and release the reservation.
            plugin.getLogger().severe("Market sale commit failed for listing " + listing.id() + ": " + error);
            refund(buyer, uuid, price, "sale commit failed");
            releaseThen(listing, uuid, () -> {
                journal.remove(attemptId);
                finish(uuid, callback, Outcome.of(Result.ERROR));
            });
        });
    }

    private void refund(Player buyer, UUID uuid, long amount, String reason) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean refunded = VaultUtil.deposit(uuid, amount);
            TransactionLogger.logNote(buyer, "MARKET BUY refund of " + amount + " coppets (" + reason + ") "
                    + (refunded ? "OK" : "FAILED"));
            if (!refunded) plugin.getLogger().severe("Failed to refund " + amount + " coppets to " + uuid);
        });
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
    }

}
