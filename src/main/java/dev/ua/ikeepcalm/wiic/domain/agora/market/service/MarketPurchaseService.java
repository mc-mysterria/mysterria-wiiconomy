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
 * in the same statement) → async Vault withdraw → journal BUY → one transaction
 * {SOLD + buyer stash row + seller ledger row (net = gross − tax) + audit} →
 * journal remove. A failed withdraw releases the reservation; a crash between
 * withdraw and commit is completed by {@link JournalRecovery} at startup.
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
            withdrawAndCommit(buyer, uuid, listing, callback);
        }, error -> {
            Result result = error instanceof PurchaseAbort abort ? abort.result : Result.ERROR;
            if (result == Result.ERROR) plugin.getLogger().severe("Market reserve failed: " + error);
            finish(uuid, callback, Outcome.of(result));
        });
    }

    private void withdrawAndCommit(Player buyer, UUID uuid, Listing listing, Consumer<Outcome> callback) {
        long price = listing.price();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean withdrawn = VaultUtil.withdraw(uuid, price);
            if (!withdrawn) {
                TransactionLogger.logNote(buyer, "MARKET BUY withdraw of " + price + " coppets failed for listing " + listing.id());
                db.transactionThenMain(conn -> {
                    ListingDao.releaseReservation(conn, listing.id(), uuid);
                    return null;
                }, ignored -> finish(uuid, callback, Outcome.of(Result.INSUFFICIENT_FUNDS)),
                   error -> {
                       plugin.getLogger().severe("Failed to release reservation " + listing.id() + ": " + error
                               + " (sweeper will release it)");
                       finish(uuid, callback, Outcome.of(Result.INSUFFICIENT_FUNDS));
                   });
                return;
            }

            try {
                journal.append(MarketJournal.Type.BUY, listing.id().toString(), uuid, price, null);
            } catch (IllegalStateException e) {
                plugin.getLogger().severe("Market journal unavailable, refunding purchase: " + e.getMessage());
                refund(buyer, uuid, price, "journal append failed");
                db.transactionThenMain(conn -> {
                    ListingDao.releaseReservation(conn, listing.id(), uuid);
                    return null;
                }, ignored -> finish(uuid, callback, Outcome.of(Result.ERROR)),
                   error -> finish(uuid, callback, Outcome.of(Result.ERROR)));
                return;
            }

            commitSale(buyer, uuid, listing, callback);
        });
    }

    private void commitSale(Player buyer, UUID uuid, Listing listing, Consumer<Outcome> callback) {
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
            journal.remove(listing.id().toString());
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
            db.transactionThenMain(conn -> {
                ListingDao.releaseReservation(conn, listing.id(), uuid);
                return null;
            }, done -> {
                journal.remove(listing.id().toString());
                finish(uuid, callback, Outcome.of(Result.ERROR));
            }, releaseError -> {
                journal.remove(listing.id().toString());
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
}
