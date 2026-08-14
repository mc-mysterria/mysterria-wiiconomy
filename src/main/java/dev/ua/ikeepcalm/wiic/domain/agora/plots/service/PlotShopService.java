package dev.ua.ikeepcalm.wiic.domain.agora.plots.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketFeedback;
import dev.ua.ikeepcalm.wiic.domain.agora.db.LedgerDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.PlotShopDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.ItemSnapshot;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.LedgerEntry;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRegion;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRental;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotShop;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.SaleNotifier;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.utils.WorldUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Stall counters: a sign on a chest, in a plot somebody rents, selling one kind of goods
 * at one price to anyone who walks up and clicks it.
 *
 * <p>This is the plot's own trade channel, and it is deliberately unlike the Broker. A
 * listing is anonymous, escrowed in the database, discoverable through the Informant and
 * delivered by courier. A counter is a physical thing: the stock is whatever is in the
 * chest right now, the price is written on a sign a passer-by can read, and the goods
 * change hands on the spot. What the two share is the money path — {@link
 * MarketConfig#saleTax} is taken as a sink and the remainder lands in the owner's ledger
 * to be claimed at the Ledger Keeper, exactly as a broker sale does. One market, one set
 * of sinks, one place proceeds are counted.
 *
 * <h2>Why stock is not a column</h2>
 * The obvious design records how many items a counter has. It is also wrong: the number
 * would be a second source of truth that can disagree with the chest, and the chest is
 * the thing the player can open and see. So stock is counted out of the live container at
 * the moment of purchase, and the sale is refused if it isn't there.
 *
 * <h2>Ordering, and what a crash can cost</h2>
 * Money first (async Vault), then stock, then the ledger — the same direction as {@code
 * ListingService} and {@code PlotService}. A failure after the withdrawal refunds the
 * buyer; a failure after the goods change hands can only leave the owner's proceeds
 * unwritten, which is logged by name and amount rather than swallowed. Nothing in the
 * sequence can hand out an item without taking payment for it.
 *
 * <p>Two locks guard the async hop: one per buyer and one per counter, so neither a
 * double-click nor two customers at the same chest can spend the same stack twice.
 */
public class PlotShopService {

    public enum CreateResult {
        SUCCESS, DISABLED, NOT_IN_PLOT, NOT_RENTER, NO_CONTAINER, CONTAINER_OUTSIDE_PLOT,
        BAD_PRICE, TOO_MANY, DUPLICATE, ERROR
    }

    public enum BuyResult {
        SUCCESS, DISABLED, UNSTOCKED, SELF_PURCHASE, CLOSED, OUT_OF_STOCK,
        INSUFFICIENT_FUNDS, BUSY, ERROR
    }

    /**
     * What a purchase actually moved, so the caller can say so.
     */
    public record Purchase(BuyResult result, long price, int amount, String itemName) {
        static Purchase of(BuyResult result) {
            return new Purchase(result, 0, 0, "");
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final Set<UUID> BUYERS_IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> SHOPS_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final PlotService plots;
    private final ItemInspector inspector;
    private final MarketFeedback feedback;
    private final SaleNotifier notifier;

    /**
     * Sign block key → counter. Authoritative for reads; every write goes to SQLite first.
     */
    private final Map<String, PlotShop> bySign = new ConcurrentHashMap<>();

    /**
     * Buyer → the counter they have armed, so a stray click can't spend their coins.
     */
    private final Map<UUID, Armed> armed = new ConcurrentHashMap<>();

    private record Armed(UUID shopId, long price, long expiresAt) {
    }

    public PlotShopService(WIIC plugin, MarketConfig config, MarketDatabase db, PlotService plots,
                           ItemInspector inspector, MarketFeedback feedback, SaleNotifier notifier) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.plots = plots;
        this.inspector = inspector;
        this.feedback = feedback;
        this.notifier = notifier;
    }

    public void load() {
        try {
            List<PlotShop> all = db.awaitLoad("plot shop", PlotShopDao::all);
            for (PlotShop shop : all) bySign.put(shop.signKey(), shop);
            plugin.getLogger().info("Loaded " + all.size() + " market stall counters");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load market stall counters: " + e);
        }
    }

    public boolean enabled() {
        return config.plotsEnabled() && config.shopsEnabled();
    }

    // -------------------------------------------------------------------------
    // Lookup
    // -------------------------------------------------------------------------

    /**
     * The counter registered at {@code block}, or null.
     *
     * <p>A registration whose sign has become air is a ghost — the sign was destroyed by
     * something that never reached {@code BlockBreakEvent}. Returning it would let anyone
     * put a block there and inherit somebody's shop, so it is deregistered on the spot
     * (the same rule {@code EntranceService} applies to its doors).
     */
    public @Nullable PlotShop at(Block block) {
        if (bySign.isEmpty()) return null;
        PlotShop shop = bySign.get(PlotShop.key(WorldUtil.id(block.getWorld()),
                block.getX(), block.getY(), block.getZ()));
        if (shop == null) return null;
        if (!(block.getState(false) instanceof Sign)) {
            remove(shop, "sign block is gone");
            return null;
        }
        return shop;
    }

    /**
     * Counters whose sign or container is {@code block} — used when either is broken.
     */
    public List<PlotShop> touching(Block block) {
        if (bySign.isEmpty()) return List.of();
        String key = PlotShop.key(WorldUtil.id(block.getWorld()), block.getX(), block.getY(), block.getZ());
        List<PlotShop> hits = new ArrayList<>();
        for (PlotShop shop : bySign.values()) {
            if (shop.signKey().equals(key) || shop.chestKey().equals(key)) hits.add(shop);
        }
        return hits;
    }

    public List<PlotShop> inPlot(String plotId) {
        List<PlotShop> hits = new ArrayList<>();
        for (PlotShop shop : bySign.values()) {
            if (shop.plotId().equals(plotId)) hits.add(shop);
        }
        return hits;
    }

    public boolean isEmpty() {
        return bySign.isEmpty();
    }

    /**
     * Live stock in the counter's container, or -1 when it can't be read (unloaded, gone).
     */
    public int stockOf(PlotShop shop) {
        ItemStack template = shop.template();
        Inventory inventory = containerOf(shop);
        if (template == null || inventory == null) return -1;
        return countStock(inventory, template);
    }

    // -------------------------------------------------------------------------
    // Building a counter
    // -------------------------------------------------------------------------

    /**
     * Registers a counter for a sign the player just wrote. The sign's own text is not
     * trusted for anything but the price — ownership comes from the plot rental, so a
     * renter cannot sign a counter into somebody else's stall.
     */
    public void create(Player owner, Block signBlock, Block container, long price, int bundle,
                       Consumer<CreateResult> callback) {
        if (!enabled()) {
            callback.accept(CreateResult.DISABLED);
            return;
        }
        PlotRegion region = plots.regionAt(signBlock.getLocation());
        if (region == null) {
            callback.accept(CreateResult.NOT_IN_PLOT);
            return;
        }
        if (!plots.canBuild(owner, signBlock.getLocation())) {
            callback.accept(CreateResult.NOT_RENTER);
            return;
        }
        if (!(container.getState(false) instanceof Container)) {
            callback.accept(CreateResult.NO_CONTAINER);
            return;
        }
        if (!region.contains(container.getLocation())) {
            // A counter reaching into a chest outside the plot would survive the eviction
            // that clears the stall, and sell from stock nobody can take back.
            callback.accept(CreateResult.CONTAINER_OUTSIDE_PLOT);
            return;
        }
        if (price < config.minPrice() || price > config.maxPrice()) {
            callback.accept(CreateResult.BAD_PRICE);
            return;
        }
        if (inPlot(region.id()).size() >= config.shopMaxPerPlot()) {
            callback.accept(CreateResult.TOO_MANY);
            return;
        }
        String signKey = PlotShop.key(WorldUtil.id(signBlock.getWorld()),
                signBlock.getX(), signBlock.getY(), signBlock.getZ());
        if (bySign.containsKey(signKey)) {
            callback.accept(CreateResult.DUPLICATE);
            return;
        }

        PlotShop shop = new PlotShop(UUID.randomUUID(), region.id(), WorldUtil.id(signBlock.getWorld()),
                signBlock.getX(), signBlock.getY(), signBlock.getZ(),
                container.getX(), container.getY(), container.getZ(),
                owner.getUniqueId(), owner.getName(), null, null, null,
                price, Math.max(1, bundle), 0, System.currentTimeMillis());

        db.transactionThenMain(conn -> {
            PlotShopDao.insert(conn, shop);
            return true;
        }, ignored -> {
            bySign.put(signKey, shop);
            callback.accept(CreateResult.SUCCESS);
        }, error -> {
            plugin.getLogger().severe("Stall counter insert failed for " + owner.getName() + ": " + error);
            callback.accept(CreateResult.ERROR);
        });
    }

    /**
     * Binds goods to a counter — the owner right-clicking their sign with the item they
     * intend to sell. Rebinding is how the price screen's item is changed; the deny list
     * is the Broker's, so a counter can't sell what the Fence would refuse.
     *
     * @return the deny message key when the item is refused, otherwise null.
     */
    public @Nullable String bind(PlotShop shop, ItemStack item, Consumer<Boolean> callback) {
        String denied = inspector.checkDenied(item);
        if (denied != null) return denied;

        ItemStack template = item.clone();
        template.setAmount(1);
        ItemSnapshot snapshot = inspector.snapshot(template);
        PlotShop bound = new PlotShop(shop.id(), shop.plotId(), shop.world(),
                shop.signX(), shop.signY(), shop.signZ(),
                shop.chestX(), shop.chestY(), shop.chestZ(),
                shop.ownerUuid(), shop.ownerName(),
                template.serializeAsBytes(), snapshot.material(), snapshot.displayName(),
                shop.price(), Math.min(shop.bundle(), template.getMaxStackSize()),
                shop.soldCount(), shop.createdAt());
        persistGoods(bound, callback);
        return null;
    }

    /**
     * Clears the goods, leaving the counter standing but closed.
     */
    public void unbind(PlotShop shop, Consumer<Boolean> callback) {
        PlotShop cleared = new PlotShop(shop.id(), shop.plotId(), shop.world(),
                shop.signX(), shop.signY(), shop.signZ(),
                shop.chestX(), shop.chestY(), shop.chestZ(),
                shop.ownerUuid(), shop.ownerName(), null, null, null,
                shop.price(), shop.bundle(), shop.soldCount(), shop.createdAt());
        persistGoods(cleared, callback);
    }

    /**
     * Changes the asking price without touching the goods.
     */
    public void reprice(PlotShop shop, long price, Consumer<Boolean> callback) {
        // Same bounds create() enforces. This has no caller yet; wiring one up without the
        // check would be an easy way to hand out a counter selling at zero or below.
        if (price < config.minPrice() || price > config.maxPrice()) {
            callback.accept(false);
            return;
        }
        PlotShop repriced = new PlotShop(shop.id(), shop.plotId(), shop.world(),
                shop.signX(), shop.signY(), shop.signZ(),
                shop.chestX(), shop.chestY(), shop.chestZ(),
                shop.ownerUuid(), shop.ownerName(), shop.itemBytes(), shop.material(), shop.displayName(),
                price, shop.bundle(), shop.soldCount(), shop.createdAt());
        persistGoods(repriced, callback);
    }

    private void persistGoods(PlotShop shop, Consumer<Boolean> callback) {
        db.transactionThenMain(conn -> {
            PlotShopDao.updateGoods(conn, shop);
            return true;
        }, ignored -> {
            bySign.put(shop.signKey(), shop);
            renderSign(shop);
            callback.accept(true);
        }, error -> {
            plugin.getLogger().severe("Stall counter update failed for " + shop.id() + ": " + error);
            callback.accept(false);
        });
    }

    public void remove(PlotShop shop, String reason) {
        bySign.remove(shop.signKey());
        armed.values().removeIf(entry -> entry.shopId().equals(shop.id()));
        db.submit(conn -> {
            PlotShopDao.delete(conn, shop.id());
            return null;
        });
        plugin.getLogger().info("Stall counter " + shop.id() + " removed (" + reason + ")");
    }

    /**
     * Every counter in a plot goes when the plot does — wired to {@code PlotService}'s eviction.
     */
    public void forgetPlot(String plotId) {
        List<PlotShop> doomed = inPlot(plotId);
        if (doomed.isEmpty()) return;
        for (PlotShop shop : doomed) {
            bySign.remove(shop.signKey());
            armed.values().removeIf(entry -> entry.shopId().equals(shop.id()));
        }
        db.submit(conn -> PlotShopDao.deleteByPlot(conn, plotId));
        plugin.getLogger().info("Removed " + doomed.size() + " stall counter(s) with plot " + plotId);
    }

    // -------------------------------------------------------------------------
    // Buying
    // -------------------------------------------------------------------------

    /**
     * Arms a purchase, or completes one already armed. The first click quotes the goods
     * and the price; a second click inside {@code plots.shops.confirm-seconds} pays. A
     * sign is a block a player brushes past, and money must never leave a wallet because
     * somebody clicked the scenery.
     *
     * @return true when this click armed the counter rather than buying from it.
     */
    public boolean arm(Player buyer, PlotShop shop) {
        Armed pending = armed.get(buyer.getUniqueId());
        long now = System.currentTimeMillis();
        if (pending != null && pending.shopId().equals(shop.id())
                && pending.price() == shop.price() && pending.expiresAt() > now) {
            armed.remove(buyer.getUniqueId());
            return false;
        }
        armed.put(buyer.getUniqueId(), new Armed(shop.id(), shop.price(), now + config.shopConfirmMs()));
        return true;
    }

    public void purchase(Player buyer, PlotShop shop, Consumer<Purchase> callback) {
        if (!enabled()) {
            callback.accept(Purchase.of(BuyResult.DISABLED));
            return;
        }
        UUID buyerId = buyer.getUniqueId();
        if (shop.ownerUuid().equals(buyerId)) {
            callback.accept(Purchase.of(BuyResult.SELF_PURCHASE));
            return;
        }
        if (!shop.isStocked()) {
            callback.accept(Purchase.of(BuyResult.UNSTOCKED));
            return;
        }
        // A counter outlives its rental only until somebody tries to use it.
        PlotRental rental = plots.rental(shop.plotId());
        if (rental == null || !rental.renterUuid().equals(shop.ownerUuid())) {
            remove(shop, "the plot is no longer rented by its owner");
            callback.accept(Purchase.of(BuyResult.CLOSED));
            return;
        }
        ItemStack template = shop.template();
        Inventory inventory = containerOf(shop);
        if (template == null || inventory == null) {
            callback.accept(Purchase.of(BuyResult.CLOSED));
            return;
        }
        int wanted = Math.max(1, Math.min(shop.bundle(), template.getMaxStackSize()));
        if (countStock(inventory, template) < wanted) {
            callback.accept(Purchase.of(BuyResult.OUT_OF_STOCK));
            return;
        }
        if (!BUYERS_IN_FLIGHT.add(buyerId)) {
            callback.accept(Purchase.of(BuyResult.BUSY));
            return;
        }
        if (!SHOPS_IN_FLIGHT.add(shop.id())) {
            BUYERS_IN_FLIGHT.remove(buyerId);
            callback.accept(Purchase.of(BuyResult.BUSY));
            return;
        }

        long price = shop.price();
        String itemName = shop.displayName() != null ? shop.displayName() : String.valueOf(shop.material());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!VaultUtil.withdraw(buyerId, price)) {
                TransactionLogger.logNote(buyer, "MARKET STALL withdraw of " + price
                        + " coppets failed at " + shop.plotId());
                finish(buyerId, shop.id(), callback, Purchase.of(BuyResult.INSUFFICIENT_FUNDS));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Re-read the container on the main thread: the async hop is exactly where
                // the owner can have emptied it, and taking a partial handful would be the
                // one outcome worse than refusing the sale.
                Inventory live = containerOf(shop);
                if (live == null || !takeStock(live, template, wanted)) {
                    refund(buyer, buyerId, price, "stock gone before the counter could hand it over");
                    finish(buyerId, shop.id(), callback, Purchase.of(BuyResult.OUT_OF_STOCK));
                    return;
                }
                ItemStack payload = template.clone();
                payload.setAmount(wanted);
                ItemUtil.giveOrDrop(buyer, payload);
                creditOwner(buyer, shop, price, wanted, itemName, callback);
            });
        });
    }

    /**
     * Sale tax to the sink, the rest to the owner's ledger — the broker's money path.
     */
    private void creditOwner(Player buyer, PlotShop shop, long price, int amount, String itemName,
                             Consumer<Purchase> callback) {
        long tax = config.saleTax(price);
        long net = price - tax;
        long now = System.currentTimeMillis();
        UUID buyerId = buyer.getUniqueId();

        db.transactionThenMain(conn -> {
            LedgerDao.insert(conn, new LedgerEntry(UUID.randomUUID(), shop.ownerUuid(),
                    price, tax, net, null, now));
            TransactionDao.log(conn, "STALL_BUY", buyerId, shop.ownerUuid(), null, price,
                    shop.plotId() + " x" + amount);
            if (tax > 0) {
                TransactionDao.log(conn, "TAX", shop.ownerUuid(), null, null, tax, "sink (stall counter)");
            }
            PlotShopDao.recordSale(conn, shop.id(), amount);
            return null;
        }, done -> {
            TransactionLogger.logNote(buyer, "MARKET STALL bought " + itemName + " x" + amount
                    + " for " + price + " coppets from " + shop.ownerName() + " (" + shop.plotId() + ")");
            notifier.sold(shop.ownerUuid());
            feedback.dealStruck(buyer);
            finish(buyerId, shop.id(), callback, new Purchase(BuyResult.SUCCESS, price, amount, itemName));
        }, error -> {
            // The goods and the coins have already changed hands; only the owner's record
            // of it failed to write. Name the debt rather than swallow it — this is the one
            // outcome staff have to repair by hand.
            plugin.getLogger().severe("Stall counter ledger write failed for " + shop.id() + ": " + error);
            plugin.getLogger().severe("  OWED " + net + " coppets to " + shop.ownerName()
                    + " (" + shop.ownerUuid() + ") for " + itemName + " x" + amount);
            finish(buyerId, shop.id(), callback, new Purchase(BuyResult.SUCCESS, price, amount, itemName));
        });
    }

    // -------------------------------------------------------------------------
    // Stock
    // -------------------------------------------------------------------------

    /**
     * The counter's live container, or null when it is unloaded or no longer a container.
     */
    private @Nullable Inventory containerOf(PlotShop shop) {
        Location location = shop.chestLocation();
        if (location == null) return null;
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return null;
        // getState(false) hands back the live tile entity, so a double chest reports (and
        // gives up) both halves, which is what the owner who filled it expects.
        return location.getBlock().getState(false) instanceof Container container
                ? container.getInventory() : null;
    }

    private static int countStock(Inventory inventory, ItemStack template) {
        int total = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.isSimilar(template)) total += stack.getAmount();
        }
        return total;
    }

    /**
     * Removes exactly {@code amount} matching items, or nothing at all.
     *
     * <p>Counted before a single slot is touched, and written back slot by slot rather than
     * through {@code Inventory#removeItem} — that method empties what it finds before
     * reporting the shortfall, which on a partial match destroys the owner's stock and
     * hands the buyer nothing.
     */
    private static boolean takeStock(Inventory inventory, ItemStack template, int amount) {
        if (countStock(inventory, template) < amount) return false;
        int remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !stack.isSimilar(template)) continue;
            int take = Math.min(remaining, stack.getAmount());
            if (take >= stack.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                inventory.setItem(slot, stack);
            }
            remaining -= take;
        }
        return remaining == 0;
    }

    // -------------------------------------------------------------------------
    // The sign face
    // -------------------------------------------------------------------------

    /**
     * Redraws the counter's sign from {@code plots.shops.sign-lines}. Both faces are
     * written: a stall sign is read from whichever side the customer happens to approach,
     * and tracking which side the owner wrote on would be a column that earns nothing.
     */
    public void renderSign(PlotShop shop) {
        Location location = shop.signLocation();
        if (location == null) return;
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        if (!(location.getBlock().getState(false) instanceof Sign sign)) return;

        List<String> templates = config.shopSignLines();
        String item = shop.displayName() != null ? shop.displayName()
                : config.message("stall-empty-line", "—");
        for (Side side : Side.values()) {
            var face = sign.getSide(side);
            for (int line = 0; line < 4; line++) {
                String raw = line < templates.size() ? templates.get(line) : "";
                face.line(line, MM.deserialize(raw
                        .replace("%item%", item)
                        .replace("%price%", PlotShop.compactPrice(shop.price()))
                        .replace("%amount%", String.valueOf(shop.bundle()))
                        .replace("%owner%", shop.ownerName())));
            }
        }
        sign.update(false, false);
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private void refund(Player buyer, UUID buyerId, long amount, String reason) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean refunded = VaultUtil.deposit(buyerId, amount);
            TransactionLogger.logNote(buyer, "MARKET STALL refund of " + amount + " coppets ("
                    + reason + ") " + (refunded ? "OK" : "FAILED"));
            if (!refunded) {
                plugin.getLogger().severe("Failed to refund " + amount + " coppets to " + buyerId);
            }
        });
    }

    private void finish(UUID buyerId, UUID shopId, Consumer<Purchase> callback, Purchase outcome) {
        if (Bukkit.isPrimaryThread()) {
            release(buyerId, shopId);
            callback.accept(outcome);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                release(buyerId, shopId);
                callback.accept(outcome);
            });
        }
    }

    private void release(UUID buyerId, UUID shopId) {
        BUYERS_IN_FLIGHT.remove(buyerId);
        SHOPS_IN_FLIGHT.remove(shopId);
    }

    /** Drops every single-flight guard. Called on module shutdown — these sets are
     *  static and would otherwise carry a stale lock across a plugin reload. */
    public static void releaseAll() {
        BUYERS_IN_FLIGHT.clear();
        SHOPS_IN_FLIGHT.clear();
    }

}
