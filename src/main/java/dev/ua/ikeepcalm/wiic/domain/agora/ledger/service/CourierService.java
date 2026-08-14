package dev.ua.ikeepcalm.wiic.domain.agora.ledger.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.CourierDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.CourierHook;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.CourierContract;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Horn contracts and courier delivery of purchases.
 *
 * <p>A player leaves a summoning horn at the Courier Post; from then on everything they
 * buy is flown to them by a postman at their own courier tier instead of waiting in the
 * market stash. Withdrawing the horn turns it back off. The horn is escrowed as bytes in
 * {@code courier_contracts} — nothing but this service ever holds it.
 *
 * <p>Delivery ordering is claim-then-dispatch: the stash row a purchase just wrote is
 * CAS-claimed first, and only a successful claim is handed to postmans. A dispatch that
 * fails reverts the claim, so the item is either in the stash or with a courier and never
 * both — the same no-dupe rule {@code StashService} follows.
 */
public class CourierService {

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final CourierHook hook;

    /** Owners with an active contract, so the purchase path and GUIs can ask for free. */
    private final Set<UUID> contracted = ConcurrentHashMap.newKeySet();

    public CourierService(WIIC plugin, MarketConfig config, MarketDatabase db, CourierHook hook) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.hook = hook;
    }

    public void load() {
        try {
            contracted.addAll(db.submit(CourierDao::allOwners).get(10, TimeUnit.SECONDS));
            plugin.getLogger().info("Loaded " + contracted.size() + " market courier contracts");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load market courier contracts: " + e);
        }
    }

    /** Whether purchases by {@code player} currently fly out by courier. Cache-only. */
    public boolean hasContract(UUID player) {
        return contracted.contains(player);
    }

    public void contract(Player player, Consumer<@Nullable CourierContract> callback) {
        db.submitThenMain(conn -> CourierDao.find(conn, player.getUniqueId()), callback, error -> {
            plugin.getLogger().severe("Courier contract lookup failed for " + player.getName() + ": " + error);
            callback.accept(null);
        });
    }

    public int deliverySeconds(String courierType) {
        return hook.deliverySeconds(courierType);
    }

    /** Whether {@code item} is a postmans summoning horn (the only depositable item). */
    public boolean isHornItem(ItemStack item) {
        return hook.isHorn(item);
    }

    // -------------------------------------------------------------------------
    // Deposit / withdraw
    // -------------------------------------------------------------------------

    public enum DepositResult { SUCCESS, NOT_A_HORN, ALREADY_CONTRACTED, ITEM_MISSING, UNAVAILABLE, ERROR }

    /**
     * Escrows {@code horn} (taken from the player's inventory on success) and switches
     * their purchases over to courier delivery. The item leaves the inventory only after
     * the clone-then-{@code removeItem} guard used everywhere else in WIIC, and comes
     * straight back if the insert fails.
     */
    public void deposit(Player player, ItemStack horn, Consumer<DepositResult> callback) {
        UUID uuid = player.getUniqueId();
        if (!hook.available()) {
            callback.accept(DepositResult.UNAVAILABLE);
            return;
        }
        if (!hook.isHorn(horn)) {
            callback.accept(DepositResult.NOT_A_HORN);
            return;
        }
        if (contracted.contains(uuid)) {
            callback.accept(DepositResult.ALREADY_CONTRACTED);
            return;
        }

        String courierType = hook.resolveCourierType(player).orElse("skeleton");
        ItemStack snapshot = horn.clone();
        snapshot.setAmount(1);
        // Take exactly one horn out; anything left un-removed means it moved meanwhile.
        ItemStack toRemove = horn.clone();
        toRemove.setAmount(1);
        if (!player.getInventory().removeItem(toRemove).isEmpty()) {
            callback.accept(DepositResult.ITEM_MISSING);
            return;
        }

        CourierContract contract = new CourierContract(uuid, snapshot.serializeAsBytes(),
                courierType, System.currentTimeMillis());
        db.transactionThenMain(conn -> {
            if (!CourierDao.insert(conn, contract)) return false;
            TransactionDao.log(conn, "COURIER_DEPOSIT", uuid, null, null, 0, courierType);
            return true;
        }, stored -> {
            if (!stored) {
                giveBack(player, snapshot);
                callback.accept(DepositResult.ALREADY_CONTRACTED);
                return;
            }
            contracted.add(uuid);
            TransactionLogger.logNote(player, "MARKET COURIER horn deposited (" + courierType + ")");
            callback.accept(DepositResult.SUCCESS);
        }, error -> {
            giveBack(player, snapshot);
            plugin.getLogger().severe("Courier deposit failed for " + player.getName() + ": " + error);
            callback.accept(DepositResult.ERROR);
        });
    }

    /**
     * Returns the escrowed horn and turns auto-delivery off. The row is deleted first and
     * the horn handed over after, so a failure loses the horn rather than duplicating it —
     * and the delete is skipped entirely when there is no room to receive it.
     */
    public void withdraw(Player player, Consumer<Boolean> callback) {
        UUID uuid = player.getUniqueId();
        if (player.getInventory().firstEmpty() == -1) {
            callback.accept(false);
            return;
        }
        db.transactionThenMain(conn -> {
            CourierContract contract = CourierDao.find(conn, uuid);
            if (contract == null || !CourierDao.delete(conn, uuid)) return null;
            TransactionDao.log(conn, "COURIER_WITHDRAW", uuid, null, null, 0, contract.courierType());
            return contract;
        }, contract -> {
            if (contract == null) {
                // Nothing was deleted, so the cache must keep saying what the table says —
                // clearing it here would strand the horn with auto-delivery already off.
                callback.accept(false);
                return;
            }
            contracted.remove(uuid);
            ItemStack horn;
            try {
                horn = ItemStack.deserializeBytes(contract.hornItemBytes());
            } catch (Exception e) {
                plugin.getLogger().severe("Corrupt escrowed horn for " + player.getName() + ": " + e);
                callback.accept(false);
                return;
            }
            giveBack(player, horn);
            TransactionLogger.logNote(player, "MARKET COURIER horn withdrawn (" + contract.courierType() + ")");
            callback.accept(true);
        }, error -> {
            plugin.getLogger().severe("Courier withdraw failed for " + player.getName() + ": " + error);
            callback.accept(false);
        });
    }

    // -------------------------------------------------------------------------
    // Delivery
    // -------------------------------------------------------------------------

    /**
     * Tries to fly a just-purchased stash row out to {@code buyer} instead of leaving it
     * for pickup. Callback reports whether a courier took it; false always means the item
     * is still safely in the stash.
     *
     * @param stashId    the row {@code MarketPurchaseService} just inserted.
     * @param itemBytes  the same item blob, so no re-read is needed.
     */
    public void tryDeliver(Player buyer, UUID stashId, byte[] itemBytes,
                           UUID sellerUuid, String sellerName, Consumer<Boolean> callback) {
        UUID uuid = buyer.getUniqueId();
        if (!contracted.contains(uuid) || !hook.available()) {
            callback.accept(false);
            return;
        }

        long fee = config.courierFee();
        if (fee <= 0) {
            claimAndDispatch(buyer, stashId, itemBytes, sellerUuid, sellerName, 0, callback);
            return;
        }
        // Optional per-delivery sink: charged before the row is claimed, refunded if the
        // hand-over then falls through.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!VaultUtil.withdraw(uuid, fee)) {
                TransactionLogger.logNote(buyer, "MARKET COURIER fee withdraw of " + fee + " coppets failed");
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () ->
                    claimAndDispatch(buyer, stashId, itemBytes, sellerUuid, sellerName, fee, callback));
        });
    }

    private void claimAndDispatch(Player buyer, UUID stashId, byte[] itemBytes,
                                  UUID sellerUuid, String sellerName, long fee, Consumer<Boolean> callback) {
        UUID uuid = buyer.getUniqueId();
        db.transactionThenMain(conn -> {
            CourierContract contract = CourierDao.find(conn, uuid);
            if (contract == null) return null;
            if (!StashDao.markClaimed(conn, stashId, System.currentTimeMillis())) return null;
            TransactionDao.log(conn, "COURIER_DELIVER", uuid, sellerUuid, null, fee, contract.courierType());
            return contract;
        }, contract -> {
            if (contract == null) {
                refundFee(buyer, uuid, fee, "no contract / stash row already claimed");
                callback.accept(false);
                return;
            }
            ItemStack item;
            try {
                item = ItemStack.deserializeBytes(itemBytes);
            } catch (Exception e) {
                plugin.getLogger().severe("Corrupt purchase blob for courier delivery to "
                        + buyer.getName() + ": " + e);
                revertClaim(stashId);
                refundFee(buyer, uuid, fee, "corrupt item blob");
                callback.accept(false);
                return;
            }

            // Re-resolve the tier from the buyer standing here rather than trusting the one
            // frozen at deposit time: postmans keys tiers to permissions, so somebody who
            // bought a premium horn since depositing would otherwise stay on the old speed
            // until they withdrew and re-deposited.
            String tier = hook.resolveCourierType(buyer).orElse(contract.courierType());
            boolean dispatched = hook.dispatch(sellerUuid, sellerName, uuid, buyer.getName(), item, tier);
            if (!dispatched) {
                revertClaim(stashId);
                refundFee(buyer, uuid, fee, "courier dispatch refused");
                callback.accept(false);
                return;
            }
            TransactionLogger.logNote(buyer, "MARKET COURIER delivery of " + item.getType().name()
                    + " x" + item.getAmount() + " via " + tier
                    + (fee > 0 ? " (fee " + fee + ")" : ""));
            callback.accept(true);
        }, error -> {
            plugin.getLogger().severe("Courier claim failed for " + buyer.getName() + ": " + error);
            refundFee(buyer, uuid, fee, "courier claim failed");
            callback.accept(false);
        });
    }

    /** Puts an unclaimed-again row back on the stash shelf after a failed hand-over. */
    private void revertClaim(UUID stashId) {
        db.submit(conn -> {
            StashDao.revertClaim(conn, stashId);
            return null;
        });
    }

    private void refundFee(Player player, UUID uuid, long fee, String reason) {
        if (fee <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean refunded = VaultUtil.deposit(uuid, fee);
            TransactionLogger.logNote(player, "MARKET COURIER fee refund of " + fee + " coppets ("
                    + reason + ") " + (refunded ? "OK" : "FAILED"));
            if (!refunded) {
                plugin.getLogger().severe("Failed to refund courier fee of " + fee + " coppets to " + uuid);
            }
        });
    }

    private void giveBack(Player player, ItemStack item) {
        ItemUtil.giveOrDrop(player, item);
    }
}
