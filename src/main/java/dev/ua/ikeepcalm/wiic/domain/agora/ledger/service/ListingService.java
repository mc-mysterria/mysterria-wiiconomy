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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Creates and cancels broker listings. Mirrors {@code PurchaseService}'s pipeline:
 * per-player single-flight lock, main-thread validation, async money leg, DB
 * transaction, refund toward the player on failure.
 *
 * <p>Listing-creation ordering: the fee is withdrawn <b>first</b> (money is
 * authoritative), then the item — already removed from the player's inventory into
 * the GUI's virtual inventory — is journaled and committed as an ACTIVE listing.
 * A DB failure refunds the fee and hands the item back to the GUI. A crash between
 * journal append and DB commit is repaired at startup by {@link JournalRecovery}
 * (item lands in the seller's stash, never lost, never duplicated).
 */
public class ListingService {

    public enum Result {
        SUCCESS, ALREADY_IN_PROGRESS, ITEM_DENIED, PRICE_OUT_OF_BOUNDS,
        DAILY_LIMIT, MAX_ACTIVE, INSUFFICIENT_FEE, ERROR
    }

    public record Outcome(Result result, String denyMessageKey, long fee) {
        public static Outcome of(Result result) {
            return new Outcome(result, null, 0);
        }
    }

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
        if (!IN_FLIGHT.add(uuid)) {
            callback.accept(Outcome.of(Result.ALREADY_IN_PROGRESS));
            return;
        }

        String denied = inspector.checkDenied(item);
        if (denied != null) {
            finish(uuid, callback, new Outcome(Result.ITEM_DENIED, denied, 0));
            return;
        }
        if (price < config.minPrice() || price > config.maxPrice()) {
            finish(uuid, callback, Outcome.of(Result.PRICE_OUT_OF_BOUNDS));
            return;
        }

        long fee = config.listingFee(price);
        ItemSnapshot snapshot = inspector.snapshot(item);
        byte[] bytes = item.serializeAsBytes();
        UUID listingId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        Listing listing = new Listing(listingId, uuid, seller.getName(), bytes,
                snapshot.material(), snapshot.amount(), snapshot.displayName(), snapshot.category(),
                snapshot.coiItem(), snapshot.coiPathway(), snapshot.coiSequence(),
                price, ListingState.ACTIVE, null, plotId, now, now + config.listingDurationMs(),
                snapshot.valueKey());

        // Money leg first: fee is a sink (never deposited anywhere), see market.yml.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (fee > 0 && !VaultUtil.withdraw(uuid, fee)) {
                TransactionLogger.logNote(seller, "MARKET LIST fee withdraw of " + fee + " coppets failed");
                Bukkit.getScheduler().runTask(plugin, () -> finish(uuid, callback, new Outcome(Result.INSUFFICIENT_FEE, null, fee)));
                return;
            }

            try {
                journal.append(MarketJournal.Type.LIST, listingId.toString(), uuid, price, bytes);
            } catch (IllegalStateException e) {
                plugin.getLogger().severe("Market journal unavailable, aborting listing: " + e.getMessage());
                refundFee(seller, uuid, fee, "journal append failed");
                Bukkit.getScheduler().runTask(plugin, () -> finish(uuid, callback, Outcome.of(Result.ERROR)));
                return;
            }

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
                finish(uuid, callback, new Outcome(Result.SUCCESS, null, fee));
            }, error -> {
                journal.remove(listingId.toString());
                refundFee(seller, uuid, fee, "listing insert failed");
                Result result = error instanceof ListingLimitException limit ? limit.result : Result.ERROR;
                if (result == Result.ERROR) {
                    plugin.getLogger().severe("Market listing insert failed for " + seller.getName() + ": " + error);
                }
                finish(uuid, callback, Outcome.of(result));
            });
        });
    }

    /** Cancels the seller's own ACTIVE listing; the item moves to their stash in the same transaction. */
    public void cancelListing(Player seller, UUID listingId, Consumer<Boolean> callback) {
        UUID uuid = seller.getUniqueId();
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
            if (success) TransactionLogger.logNote(seller, "MARKET CANCEL listing " + listingId + " -> stash");
            callback.accept(success);
        }, error -> {
            IN_FLIGHT.remove(uuid);
            plugin.getLogger().severe("Market cancel failed for " + seller.getName() + ": " + error);
            callback.accept(false);
        });
    }

    private void refundFee(Player seller, UUID uuid, long fee, String reason) {
        if (fee <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean refunded = VaultUtil.deposit(uuid, fee);
            TransactionLogger.logNote(seller, "MARKET LIST fee refund of " + fee + " coppets ("
                    + reason + ") " + (refunded ? "OK" : "FAILED"));
            if (!refunded) {
                plugin.getLogger().severe("Failed to refund listing fee of " + fee + " coppets to " + uuid);
            }
        });
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
}
