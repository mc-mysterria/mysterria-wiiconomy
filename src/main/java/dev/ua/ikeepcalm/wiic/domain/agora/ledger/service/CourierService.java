package dev.ua.ikeepcalm.wiic.domain.agora.ledger.service;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.CourierDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.CourierHook;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.CourierContract;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.utils.MysterriaAuditBridge;
import org.bukkit.Bukkit;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
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
            contracted.addAll(db.awaitLoad("courier contract", CourierDao::allOwners));
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
        return !ItemInspector.containsTemporaryItem(item) && hook.isHorn(item);
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
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("courier-contract", uuid);
        if (!hook.available()) {
            callback.accept(DepositResult.UNAVAILABLE);
            return;
        }
        if (!isHornItem(horn)) {
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
                MysterriaAuditBridge.emit("courier.contract.failed", false, uuid, uuid, null, identity,
                        "courier contract already exists", MysterriaAuditBridge.metadata(
                                Map.of("courier_type", courierType), MysterriaAuditBridge.itemMetadata(snapshot)));
                callback.accept(DepositResult.ALREADY_CONTRACTED);
                return;
            }
            contracted.add(uuid);
            TransactionLogger.logNote(player, "MARKET COURIER horn deposited (" + courierType + ")");
            MysterriaAuditBridge.emit("courier.contract.created", true, uuid, uuid, null, identity,
                    "courier contract committed", MysterriaAuditBridge.metadata(
                            Map.of("courier_type", courierType), MysterriaAuditBridge.itemMetadata(snapshot)));
            callback.accept(DepositResult.SUCCESS);
        }, error -> {
            giveBack(player, snapshot);
            plugin.getLogger().severe("Courier deposit failed for " + player.getName() + ": " + error);
            MysterriaAuditBridge.emit("courier.contract.failed", false, uuid, uuid, null, identity,
                    "courier contract write failed", MysterriaAuditBridge.metadata(
                            Map.of("courier_type", courierType), MysterriaAuditBridge.itemMetadata(snapshot)));
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
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.identity("courier-contract", uuid);
        if (player.getInventory().firstEmpty() == -1) {
            callback.accept(false);
            return;
        }
        // Deserialize on the main thread before deleting the escrow row. Invalid or
        // temporary horns stay available for staff review instead of entering circulation.
        db.transactionThenMain(conn -> CourierDao.find(conn, uuid), contract -> {
            if (contract == null) {
                MysterriaAuditBridge.emit("courier.contract.withdraw_failed", false, uuid, uuid, null, identity,
                        "no courier contract to withdraw", Map.of());
                callback.accept(false);
                return;
            }
            ItemStack horn;
            try {
                horn = ItemStack.deserializeBytes(contract.hornItemBytes());
                if (ItemInspector.containsTemporaryItem(horn)) {
                    plugin.getLogger().warning("Withheld temporary courier horn for " + uuid);
                    MysterriaAuditBridge.emit("courier.contract.withdraw_failed", false, uuid, uuid, null, identity,
                            "escrowed horn temporary; contract kept for review", MysterriaAuditBridge.metadata(
                                    Map.of("courier_type", contract.courierType(), "contract_deleted", false),
                                    MysterriaAuditBridge.itemMetadata(horn)));
                    callback.accept(false);
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Corrupt escrowed horn for " + player.getName() + ": " + e);
                MysterriaAuditBridge.emit("courier.contract.withdraw_failed", false, uuid, uuid, null, identity,
                        "escrowed horn corrupt; contract kept for review",
                        Map.of("courier_type", contract.courierType(), "contract_deleted", false));
                callback.accept(false);
                return;
            }
            db.transactionThenMain(conn -> {
                CourierContract current = CourierDao.find(conn, uuid);
                if (current == null || current.depositedAt() != contract.depositedAt()
                        || !java.util.Arrays.equals(current.hornItemBytes(), contract.hornItemBytes())
                        || !CourierDao.delete(conn, uuid)) return false;
                TransactionDao.log(conn, "COURIER_WITHDRAW", uuid, null, null, 0, contract.courierType());
                return true;
            }, removed -> {
                if (!removed) {
                    MysterriaAuditBridge.emit("courier.contract.withdraw_failed", false, uuid, uuid, null, identity,
                            "courier contract changed before withdraw", Map.of("courier_type", contract.courierType()));
                    callback.accept(false);
                    return;
                }
                contracted.remove(uuid);
                giveBack(player, horn);
                TransactionLogger.logNote(player, "MARKET COURIER horn withdrawn (" + contract.courierType() + ")");
                MysterriaAuditBridge.emit("courier.contract.withdrawn", true, uuid, uuid, null, identity,
                        "courier contract withdrawn", MysterriaAuditBridge.metadata(
                                Map.of("courier_type", contract.courierType()), MysterriaAuditBridge.itemMetadata(horn)));
                callback.accept(true);
            }, error -> {
                plugin.getLogger().severe("Courier withdraw failed for " + player.getName() + ": " + error);
                MysterriaAuditBridge.emit("courier.contract.withdraw_failed", false, uuid, uuid, null, identity,
                        "courier contract withdraw failed", Map.of());
                callback.accept(false);
            });
        }, error -> {
            plugin.getLogger().severe("Courier read failed for " + player.getName() + ": " + error);
            MysterriaAuditBridge.emit("courier.contract.withdraw_failed", false, uuid, uuid, null, identity,
                    "courier contract read failed", Map.of());
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
                           UUID sellerUuid, String sellerName,
                           MysterriaAuditBridge.AuditIdentity identity,
                           Consumer<Boolean> callback) {
        UUID uuid = buyer.getUniqueId();
        if (!contracted.contains(uuid) || !hook.available()) {
            callback.accept(false);
            return;
        }

        long fee = config.courierFee();
        if (fee <= 0) {
            claimAndDispatch(buyer, stashId, itemBytes, sellerUuid, sellerName, 0, identity, callback);
            return;
        }
        // Optional per-delivery sink. Affordability is checked here but the money is only
        // taken once a courier has actually accepted the goods (see claimAndDispatch): the
        // fee is small and unjournaled, so the one direction that must never happen is
        // charging for a delivery that a crash or a refusal then cancelled.
        VaultUtil.getBalance(uuid).thenAccept(balance -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (balance < fee) {
                TransactionLogger.logNote(buyer, "MARKET COURIER fee of " + fee + " coppets unaffordable");
                emitFeeDenied(buyer, stashId, identity, fee, balance);
                callback.accept(false);
                return;
            }
            claimAndDispatch(buyer, stashId, itemBytes, sellerUuid, sellerName, fee, identity, callback);
        }));
    }

    private static void emitFeeDenied(Player buyer, UUID stashId,
                                      MysterriaAuditBridge.AuditIdentity identity, long fee, double balance) {
        UUID uuid = buyer.getUniqueId();
        MysterriaAuditBridge.emit("courier.fee.denied", AuditOutcome.DENIED, uuid, uuid, stashId, identity,
                "courier fee unaffordable",
                Map.of("fee", fee, "currency", "coppets", "stash_id", stashId.toString(), "balance", balance));
    }

    private void claimAndDispatch(Player buyer, UUID stashId, byte[] itemBytes,
                                  UUID sellerUuid, String sellerName, long fee,
                                  MysterriaAuditBridge.AuditIdentity identity,
                                  Consumer<Boolean> callback) {
        UUID uuid = buyer.getUniqueId();
        db.transactionThenMain(conn -> {
            CourierContract contract = CourierDao.find(conn, uuid);
            if (contract == null) return null;
            if (!StashDao.markClaimed(conn, stashId, System.currentTimeMillis())) return null;
            TransactionDao.log(conn, "COURIER_DELIVER", uuid, sellerUuid, null, fee, contract.courierType());
            return contract;
        }, contract -> {
            if (contract == null) {
                MysterriaAuditBridge.emit("courier.delivery.failed", false, uuid, sellerUuid, stashId, identity,
                        "no courier contract or stash row already claimed", MysterriaAuditBridge.moneyMetadata(0,
                                Map.of("fee", fee, "courier_type", "unknown",
                                        "stash_id", stashId.toString())));
                callback.accept(false);
                return;
            }
            ItemStack item;
            try {
                item = ItemStack.deserializeBytes(itemBytes);
                if (ItemInspector.containsTemporaryItem(ItemStack.deserializeBytes(contract.hornItemBytes()))) {
                    plugin.getLogger().warning("Withheld delivery using temporary courier horn for " + uuid);
                    revertClaim(stashId);
                    MysterriaAuditBridge.emit("courier.delivery.failed", false, uuid, sellerUuid, stashId, identity,
                            "temporary courier horn; stash claim reverted", MysterriaAuditBridge.moneyMetadata(0,
                                    MysterriaAuditBridge.metadata(Map.of("fee", fee, "courier_type", contract.courierType(),
                                            "stash_id", stashId.toString()), MysterriaAuditBridge.itemMetadata(item))));
                    callback.accept(false);
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Corrupt purchase blob for courier delivery to "
                        + buyer.getName() + ": " + e);
                revertClaim(stashId);
                MysterriaAuditBridge.emit("courier.delivery.failed", false, uuid, sellerUuid, stashId, identity,
                        "purchase item blob corrupt; stash claim reverted", MysterriaAuditBridge.moneyMetadata(0,
                                Map.of("fee", fee, "courier_type", contract.courierType(),
                                        "stash_id", stashId.toString())));
                callback.accept(false);
                return;
            }

            if (ItemInspector.containsTemporaryItem(item)) {
                plugin.getLogger().warning("Withheld temporary courier stash item " + stashId);
                revertClaim(stashId);
                MysterriaAuditBridge.emit("courier.delivery.failed", false, uuid, sellerUuid, stashId, identity,
                        "temporary item; stash claim reverted", MysterriaAuditBridge.moneyMetadata(0,
                                MysterriaAuditBridge.metadata(Map.of("fee", fee, "courier_type", contract.courierType(),
                                        "stash_id", stashId.toString()), MysterriaAuditBridge.itemMetadata(item))));
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
                MysterriaAuditBridge.emit("courier.delivery.failed", false, uuid, sellerUuid, stashId, identity,
                        "courier rejected delivery", MysterriaAuditBridge.moneyMetadata(0,
                                MysterriaAuditBridge.metadata(
                                        Map.of("fee", fee, "courier_type", tier,
                                                "stash_id", stashId.toString()),
                                        MysterriaAuditBridge.itemMetadata(item))));
                callback.accept(false);
                return;
            }
            chargeFee(buyer, uuid, fee, identity);
            TransactionLogger.logNote(buyer, "MARKET COURIER delivery of " + item.getType().name()
                    + " x" + item.getAmount() + " via " + tier
                    + (fee > 0 ? " (fee " + fee + ")" : ""));
            MysterriaAuditBridge.emit("courier.delivery.dispatched", true, uuid, sellerUuid, stashId, identity,
                    "courier accepted delivery", MysterriaAuditBridge.moneyMetadata(0,
                            MysterriaAuditBridge.metadata(Map.of("fee", fee, "courier_type", tier,
                                            "stash_id", stashId.toString()), MysterriaAuditBridge.itemMetadata(item))));
            callback.accept(true);
        }, error -> {
            plugin.getLogger().severe("Courier claim failed for " + buyer.getName() + ": " + error);
            MysterriaAuditBridge.emit("courier.delivery.failed", false, uuid, sellerUuid, stashId, identity,
                    "courier claim failed", MysterriaAuditBridge.moneyMetadata(0,
                            Map.of("fee", fee, "courier_type", "unknown",
                                    "stash_id", stashId.toString())));
            callback.accept(false);
        });
    }

    /** Puts an unclaimed-again row back on the stash shelf after a failed hand-over. */
    private void revertClaim(UUID stashId) {
        db.submit(conn -> {
            StashDao.revertClaim(conn, stashId);
            return null;
        }).exceptionally(error -> {
            // The row is marked claimed and no courier took it. Silence here would lose the
            // purchase outright, so name the row for manual restoration.
            plugin.getLogger().severe("Failed to release undelivered courier stash row " + stashId
                    + ": " + error + " — row stranded as claimed");
            return null;
        });
    }

    /**
     * Takes the delivery fee once the courier has the goods. Nothing is riding on this
     * succeeding — the delivery is already done and the sink is optional — so a failure is
     * logged and forgiven rather than unwound.
     */
    private void chargeFee(Player player, UUID uuid, long fee,
                           MysterriaAuditBridge.AuditIdentity identity) {
        if (fee <= 0) return;
        // Entity position may only be read on the main thread; the async rows below reuse it.
        Map<String, Object> location = MysterriaAuditBridge.playerLocation(player);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BigDecimal balanceBefore = VaultUtil.balance(uuid);
            // An uncertain fee is reported by the checked helper and, like a refusal, is
            // neither retried nor refunded: the delivery is already dispatched.
            VaultUtil.Payment payment = VaultUtil.withdrawChecked(uuid, fee, "courier delivery fee");
            if (payment == VaultUtil.Payment.INDETERMINATE) {
                MysterriaAuditBridge.emitPaymentIndeterminate("courier.fee", uuid, uuid, null, identity, fee,
                        balanceBefore, VaultUtil.balance(uuid), MysterriaAuditBridge.metadata(Map.of("fee", fee), location));
            } else if (!payment.succeeded()) {
                TransactionLogger.logNote(player, "MARKET COURIER fee of " + fee + " coppets went uncollected");
                MysterriaAuditBridge.emit("courier.fee.failed", false, uuid, uuid, null, identity,
                        "courier fee uncollected", MysterriaAuditBridge.moneyMetadata(0,
                                balanceBefore, VaultUtil.balance(uuid), MysterriaAuditBridge.metadata(Map.of("fee", fee), location)));
                plugin.getLogger().warning("Courier fee of " + fee + " coppets could not be collected from " + uuid);
            } else {
                MysterriaAuditBridge.emit("courier.fee.collected", true, uuid, uuid, null, identity,
                        "courier fee collected", MysterriaAuditBridge.moneyMetadata(-fee,
                                balanceBefore, VaultUtil.balance(uuid), MysterriaAuditBridge.metadata(Map.of("fee", fee), location)));
            }
        });
    }

    /**
     * Hands an escrowed item back, falling back to the stash if the player has already gone.
     * The contract row is deleted before this runs, so there is nothing else still holding
     * the horn — losing it here would lose it for good.
     */
    private void giveBack(Player player, ItemStack item) {
        if (ItemUtil.giveOrDrop(player, item)) return;
        UUID owner = player.getUniqueId();
        db.transactionThenMain(conn -> {
            StashDao.insert(conn, new StashItem(UUID.randomUUID(), owner, item.serializeAsBytes(),
                    item.getType(), item.getAmount(), null, StashItem.SOURCE_RECOVERY,
                    "courier escrow", System.currentTimeMillis()));
            return null;
        }, ignored -> plugin.getLogger().warning("Escrowed courier item for " + owner
                + " went to their stash — they were offline when it came back"),
           error -> plugin.getLogger().severe("Lost escrowed courier item for " + owner
                   + ": neither returnable nor stashable: " + error));
    }
}
