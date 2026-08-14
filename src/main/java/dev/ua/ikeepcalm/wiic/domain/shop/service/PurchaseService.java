package dev.ua.ikeepcalm.wiic.domain.shop.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.shop.model.MarketIndex;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopCatalog;
import dev.ua.ikeepcalm.wiic.config.ShopConfig;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopEntry;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopPricing;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The single money-touching pipeline behind {@code /shop}. Every purchase goes through
 * {@link #purchase}, which is the only place that calls {@link VaultUtil#withdraw}
 * for a shop transaction.
 *
 * <p>Structural safety property: a purchase only ever <b>adds</b> items to the player's
 * inventory and never reads or removes anything from it. There is no inventory snapshot
 * to desync, which is the bug class that produced WIIC's historic dupes in the vault
 * deposit/withdraw/sell flow (see {@code VaultGUI}'s clone-before-mutate comments and
 * {@code WalletListener}'s InvUI-window inventory lockdown, which this GUI inherits
 * automatically). The worst possible outcome here is a charged-but-undelivered purchase,
 * which is refunded and logged — never a duplicated item, never free goods.
 *
 * <p>Call {@link #purchase} from the main thread; it hops to async for the Vault call
 * and back to main for delivery, exactly like {@code VaultGUI}'s deposit/withdraw/sell.
 */
public class PurchaseService {

    public enum Result {
        SUCCESS, ALREADY_IN_PROGRESS, COOLDOWN, NOT_PURCHASABLE, INVALID_AMOUNT,
        PRICE_CHANGED, INSUFFICIENT_FUNDS, WITHDRAW_FAILED, PLAYER_OFFLINE
    }

    public record PurchaseOutcome(Result result, long chargedUnitPrice, long chargedTotal, int delivered, int droppedStacks) {
        public boolean success() {
            return result == Result.SUCCESS;
        }
    }

    // Single-flight lock: the primary defence against spam-clicking, double packets,
    // and delayed/replayed packets — a second purchase attempt for the same player
    // cannot enter the pipeline while one is already in flight, regardless of timing.
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private final WIIC plugin;
    private final ShopConfig shopConfig;
    private final ShopCatalog catalog;
    private final ShopPricing pricing;
    private final MarketIndex marketIndex;
    private final Map<UUID, Long> lastPurchaseAt = new ConcurrentHashMap<>();

    public PurchaseService(WIIC plugin, ShopConfig shopConfig, ShopCatalog catalog, ShopPricing pricing, MarketIndex marketIndex) {
        this.plugin = plugin;
        this.shopConfig = shopConfig;
        this.catalog = catalog;
        this.pricing = pricing;
        this.marketIndex = marketIndex;
    }

    /**
     * Attempts to buy {@code amount} of {@code material} for {@code player}.
     *
     * @param quotedUnitPrice the unit price shown to the player when they confirmed;
     *                        the player is never charged more than this per unit.
     * @param callback        invoked on the main thread with the outcome.
     */
    public void purchase(Player player, Material material, int amount, long quotedUnitPrice, Consumer<PurchaseOutcome> callback) {
        UUID uuid = player.getUniqueId();

        if (!IN_FLIGHT.add(uuid)) {
            callback.accept(new PurchaseOutcome(Result.ALREADY_IN_PROGRESS, 0, 0, 0, 0));
            return;
        }

        long last = lastPurchaseAt.getOrDefault(uuid, 0L);
        if (System.currentTimeMillis() - last < shopConfig.cooldownMs()) {
            finish(uuid, callback, new PurchaseOutcome(Result.COOLDOWN, 0, 0, 0, 0));
            return;
        }

        ShopEntry entry = catalog.get(material);
        if (entry == null) {
            finish(uuid, callback, new PurchaseOutcome(Result.NOT_PURCHASABLE, 0, 0, 0, 0));
            return;
        }

        if (amount < 1 || amount > shopConfig.maxPerPurchase()) {
            finish(uuid, callback, new PurchaseOutcome(Result.INVALID_AMOUNT, 0, 0, 0, 0));
            return;
        }

        long liveUnitPrice = pricing.unitPrice(material);
        if (liveUnitPrice < 0) {
            finish(uuid, callback, new PurchaseOutcome(Result.NOT_PURCHASABLE, 0, 0, 0, 0));
            return;
        }
        if (liveUnitPrice > quotedUnitPrice) {
            // Never charge more than what the player was shown; make them re-confirm instead.
            finish(uuid, callback, new PurchaseOutcome(Result.PRICE_CHANGED, liveUnitPrice, 0, 0, 0));
            return;
        }
        long chargedUnitPrice = liveUnitPrice;
        long total = chargedUnitPrice * amount;
        double indexAtPurchase = marketIndex.currentIndex();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BigDecimal before = currentBalance(uuid);
            TransactionLogger.logBalance(player, before, "before shop purchase");

            if (before.compareTo(BigDecimal.valueOf(total)) < 0) {
                TransactionLogger.logPurchase(player, material, amount, total, indexAtPurchase, false);
                Bukkit.getScheduler().runTask(plugin, () ->
                        finish(uuid, callback, new PurchaseOutcome(Result.INSUFFICIENT_FUNDS, chargedUnitPrice, total, 0, 0)));
                return;
            }

            boolean withdrawn = VaultUtil.withdraw(uuid, total);
            if (!withdrawn) {
                TransactionLogger.logPurchase(player, material, amount, total, indexAtPurchase, false);
                plugin.getLogger().warning("Shop withdraw of " + total + " coppets failed for " + player.getName() + " (" + uuid + ")");
                Bukkit.getScheduler().runTask(plugin, () ->
                        finish(uuid, callback, new PurchaseOutcome(Result.WITHDRAW_FAILED, chargedUnitPrice, total, 0, 0)));
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online == null || !online.isValid()) {
                    // Left between charge and delivery — refund. Money is authoritative,
                    // delivery is the fallible step, so this is the safe failure direction.
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        boolean refunded = VaultUtil.deposit(uuid, total);
                        TransactionLogger.logNote(player, "PURCHASE delivery aborted (offline) — refund of " + total
                                + " coppets " + (refunded ? "OK" : "FAILED"));
                        if (!refunded) {
                            plugin.getLogger().severe("Failed to refund " + total + " coppets to offline player "
                                    + player.getName() + " (" + uuid + ") after aborted shop delivery!");
                        }
                    });
                    finish(uuid, callback, new PurchaseOutcome(Result.PLAYER_OFFLINE, chargedUnitPrice, total, 0, 0));
                    return;
                }

                // The money is already gone by this point, so nothing below may be allowed to
                // escape without settling: an exception here used to skip finish() entirely,
                // leaving the buyer charged, undelivered, and locked out of every future
                // purchase by a permanently held IN_FLIGHT entry.
                try {
                    DeliveryResult delivery = deliver(online, material, amount);
                    TransactionLogger.logPurchase(online, material, amount, total, indexAtPurchase, true);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                            TransactionLogger.logBalance(online, currentBalance(uuid), "after shop purchase"));
                    finish(uuid, callback, new PurchaseOutcome(Result.SUCCESS, chargedUnitPrice, total, delivery.delivered(), delivery.droppedStacks()));
                } catch (Throwable t) {
                    plugin.getLogger().severe("Shop delivery failed for " + online.getName()
                            + " after charging " + total + " coppets, refunding: " + t);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        boolean refunded = VaultUtil.deposit(uuid, total);
                        TransactionLogger.logNote(player, "PURCHASE delivery failed — refund of " + total
                                + " coppets " + (refunded ? "OK" : "FAILED"));
                        if (!refunded) {
                            plugin.getLogger().severe("Failed to refund " + total + " coppets to "
                                    + player.getName() + " (" + uuid + ") after failed shop delivery!");
                        }
                    });
                    finish(uuid, callback, new PurchaseOutcome(Result.WITHDRAW_FAILED, chargedUnitPrice, total, 0, 0));
                }
            });
        });
    }

    private void finish(UUID uuid, Consumer<PurchaseOutcome> callback, PurchaseOutcome outcome) {
        lastPurchaseAt.put(uuid, System.currentTimeMillis());
        IN_FLIGHT.remove(uuid);
        callback.accept(outcome);
    }

    /**
     * Builds fresh, max-size {@link ItemStack}s (never a clone of a GUI preview item),
     * gives what fits, and drops the rest at the player's feet as consolidated stacks
     * — buying 3456 blocks with no space left drops &le;54 stacks, not 3456 entities.
     */
    private DeliveryResult deliver(Player player, Material material, int amount) {
        int maxStack = material.getMaxStackSize();
        int remaining = amount;
        int droppedStacks = 0;
        while (remaining > 0) {
            int stackSize = Math.min(maxStack, remaining);
            ItemStack stack = new ItemStack(material, stackSize);
            Map<Integer, ItemStack> notAdded = player.getInventory().addItem(stack);
            for (ItemStack leftover : notAdded.values()) {
                player.getWorld().dropItem(player.getLocation(), leftover);
                droppedStacks++;
            }
            remaining -= stackSize;
        }
        return new DeliveryResult(amount, droppedStacks);
    }

    private record DeliveryResult(int delivered, int droppedStacks) {}

    private static BigDecimal currentBalance(UUID uuid) {
        if (WIIC.getEcon() == null) return BigDecimal.ZERO;
        BigDecimal balance = WIIC.getEcon().balance("iConomyUnlocked", uuid);
        return balance != null ? balance : BigDecimal.ZERO;
    }

    /** Drops every single-flight guard. Called from onDisable — the set is static and
     *  would otherwise carry a stale lock across a plugin reload. */
    public static void releaseAll() {
        IN_FLIGHT.clear();
    }

}
