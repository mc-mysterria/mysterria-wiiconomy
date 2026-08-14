package dev.ua.ikeepcalm.wiic.domain.agora.plots.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.PlotDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRegion;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRental;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.model.PlotVendorSpawner;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.utils.WorldUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Owns prestige plots: the config-defined regions, who rents them, build rights
 * inside the market world, and the upkeep → grace → eviction lifecycle.
 *
 * <p>Rental rows are mirrored in an in-memory cache because {@link #canBuild} sits on
 * the block-event hot path and must never touch the DB. The cache is authoritative for
 * reads; every write goes to SQLite first and updates the cache in the commit
 * continuation (same discipline as {@code EntranceService}).
 *
 * <p>Money follows the {@code ListingService} idiom: async Vault withdraw first (rent
 * is a sink), DB claim second, refund toward the player if the claim fails.
 *
 * <p>Eviction is deliberately clear-then-commit: container contents are pulled out and
 * the chests emptied <b>before</b> the stash rows commit, so the failure direction is a
 * logged loss rather than a dupe — the same house rule {@code StashService} documents.
 */
public class PlotService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public enum RentResult { SUCCESS, DISABLED, UNKNOWN_PLOT, ALREADY_RENTED, MAX_PLOTS, INSUFFICIENT_FUNDS, IN_PROGRESS, ERROR }

    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final ItemInspector inspector;
    private final @Nullable PlotVendorSpawner vendors;

    /** Region definitions from market.yml, in config order. Rebuilt on reload. */
    private final Map<String, PlotRegion> regions = new LinkedHashMap<>();
    /** plotId → current rental. Absent means available. */
    private final Map<String, PlotRental> rentals = new ConcurrentHashMap<>();
    /** Plots whose eviction is mid-flight, so the upkeep task can't double-evict. */
    private final Set<String> evicting = ConcurrentHashMap.newKeySet();
    /** plotId → when its renter was last told the rent is overdue. */
    private final Map<String, Long> overdueNotified = new ConcurrentHashMap<>();

    private static final long OVERDUE_NOTICE_MS = 60L * 60L * 1000L;

    private BukkitTask upkeepTask;

    /**
     * Notified with a plot id once its rental has been cleared. Set by the module to
     * {@code PlotShopService::forgetPlot} — the stall's counters go with the stall.
     * Kept as a callback rather than a field of type {@code PlotShopService} so the shop
     * service can depend on this one without the two depending on each other.
     */
    private @Nullable Consumer<String> onEvicted;

    public void onEvicted(Consumer<String> listener) {
        this.onEvicted = listener;
    }

    public PlotService(WIIC plugin, MarketConfig config, MarketDatabase db,
                       ItemInspector inspector, @Nullable PlotVendorSpawner vendors) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.inspector = inspector;
        this.vendors = vendors;
    }

    /** Loads regions and rentals, then starts the upkeep timer. Called once from the module. */
    public void load() {
        reloadRegions();
        try {
            List<PlotRental> all = db.awaitLoad("plot rental", PlotDao::all);
            for (PlotRental rental : all) rentals.put(rental.plotId(), rental);
            plugin.getLogger().info("Loaded " + regions.size() + " market plots (" + all.size() + " rented)");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load market plot rentals: " + e);
        }
        long interval = config.plotUpkeepIntervalTicks();
        upkeepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runUpkeep, interval, interval);
    }

    public void shutdown() {
        if (upkeepTask != null) upkeepTask.cancel();
    }

    /** Re-reads {@code plots.regions} from market.yml (wired into {@code /wiicmarket reload}). */
    public void reloadRegions() {
        regions.clear();
        for (PlotRegion region : config.plotRegions()) regions.put(region.id(), region);
    }

    // -------------------------------------------------------------------------
    // Lookup
    // -------------------------------------------------------------------------

    public boolean enabled() {
        return config.plotsEnabled();
    }

    public Collection<PlotRegion> allRegions() {
        return List.copyOf(regions.values());
    }

    public @Nullable PlotRegion region(String plotId) {
        return regions.get(plotId);
    }

    public @Nullable PlotRental rental(String plotId) {
        return rentals.get(plotId);
    }

    /** The plot containing {@code location}, or null. Main-thread cheap — no DB, no allocation. */
    public @Nullable PlotRegion regionAt(Location location) {
        if (!config.isMarketWorld(location.getWorld())) return null;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        for (PlotRegion region : regions.values()) {
            if (region.contains(x, y, z)) return region;
        }
        return null;
    }

    /**
     * The first defined plot whose cuboid intersects the corners {@code a}–{@code b},
     * ignoring {@code excludeId}. Overlapping plots would give two renters rights over
     * the same blocks, so {@code /wiicmarket plot define} refuses them.
     */
    public @Nullable PlotRegion overlapping(int[] a, int[] b, @Nullable String excludeId) {
        int minX = Math.min(a[0], b[0]);
        int maxX = Math.max(a[0], b[0]);
        int minY = Math.min(a[1], b[1]);
        int maxY = Math.max(a[1], b[1]);
        int minZ = Math.min(a[2], b[2]);
        int maxZ = Math.max(a[2], b[2]);
        for (PlotRegion region : regions.values()) {
            if (region.id().equals(excludeId)) continue;
            if (minX <= region.maxX() && maxX >= region.minX()
                    && minY <= region.maxY() && maxY >= region.minY()
                    && minZ <= region.maxZ() && maxZ >= region.minZ()) {
                return region;
            }
        }
        return null;
    }

    /**
     * Whether {@code player} may modify blocks/containers at {@code location} inside the
     * market world: admins anywhere, renters within their own plot while rent holds
     * (grace included). Everyone else, everywhere else: no.
     */
    public boolean canBuild(Player player, Location location) {
        if (player.hasPermission(MarketConfig.ADMIN_PERMISSION)) return true;
        PlotRegion region = regionAt(location);
        if (region == null) return false;
        PlotRental rental = rentals.get(region.id());
        if (rental == null || !rental.renterUuid().equals(player.getUniqueId())) return false;
        long now = System.currentTimeMillis();
        return rental.isPaid(now) || rental.isInGrace(now, config.plotGraceMs());
    }

    public int rentedCount(UUID renter) {
        int count = 0;
        for (PlotRental rental : rentals.values()) {
            if (rental.renterUuid().equals(renter)) count++;
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Renting
    // -------------------------------------------------------------------------

    /**
     * Rents a free plot for one period. Withdraws {@link MarketConfig#plotRentPrice()}
     * (a sink) before the DB claim; a lost race refunds it.
     */
    public void rent(Player player, String plotId, Consumer<RentResult> callback) {
        UUID uuid = player.getUniqueId();
        if (!enabled()) {
            callback.accept(RentResult.DISABLED);
            return;
        }
        PlotRegion region = regions.get(plotId);
        if (region == null) {
            callback.accept(RentResult.UNKNOWN_PLOT);
            return;
        }
        if (rentals.containsKey(plotId)) {
            callback.accept(RentResult.ALREADY_RENTED);
            return;
        }
        if (rentedCount(uuid) >= config.plotMaxPerPlayer()) {
            callback.accept(RentResult.MAX_PLOTS);
            return;
        }
        if (!IN_FLIGHT.add(uuid)) {
            callback.accept(RentResult.IN_PROGRESS);
            return;
        }

        long price = config.plotRentPrice();
        long now = System.currentTimeMillis();
        long paidUntil = now + config.plotPeriodMs();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (price > 0 && !VaultUtil.withdraw(uuid, price)) {
                TransactionLogger.logNote(player, "MARKET PLOT rent withdraw of " + price + " coppets failed");
                finish(uuid, callback, RentResult.INSUFFICIENT_FUNDS);
                return;
            }
            db.transactionThenMain(conn -> {
                if (PlotDao.countByRenter(conn, uuid) >= config.plotMaxPerPlayer()) {
                    throw new PlotDenied(RentResult.MAX_PLOTS);
                }
                if (!PlotDao.claim(conn, plotId, uuid, player.getName(), now, paidUntil)) {
                    throw new PlotDenied(RentResult.ALREADY_RENTED);
                }
                TransactionDao.log(conn, "PLOT_RENT", uuid, null, null, price, plotId);
                return new PlotRental(plotId, uuid, player.getName(), now, paidUntil, null);
            }, rental -> {
                rentals.put(plotId, rental);
                TransactionLogger.logNote(player, "MARKET PLOT rent " + plotId + " for " + price
                        + " coppets until " + paidUntil);
                spawnVendor(region, plotId);
                IN_FLIGHT.remove(uuid);
                callback.accept(RentResult.SUCCESS);
            }, error -> {
                RentResult result = error instanceof PlotDenied denied ? denied.result : RentResult.ERROR;
                refund(player, uuid, price, "plot claim rejected: " + result);
                if (result == RentResult.ERROR) {
                    plugin.getLogger().severe("Plot rent failed for " + player.getName() + " on " + plotId + ": " + error);
                }
                finish(uuid, callback, result);
            });
        });
    }

    /** Pays for one more period on a plot the player already holds. */
    public void extend(Player player, String plotId, Consumer<RentResult> callback) {
        UUID uuid = player.getUniqueId();
        PlotRental current = rentals.get(plotId);
        if (current == null || !current.renterUuid().equals(uuid)) {
            callback.accept(RentResult.UNKNOWN_PLOT);
            return;
        }
        if (!IN_FLIGHT.add(uuid)) {
            callback.accept(RentResult.IN_PROGRESS);
            return;
        }

        long price = config.plotRentPrice();
        long now = System.currentTimeMillis();
        long period = config.plotPeriodMs();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (price > 0 && !VaultUtil.withdraw(uuid, price)) {
                TransactionLogger.logNote(player, "MARKET PLOT upkeep withdraw of " + price + " coppets failed");
                finish(uuid, callback, RentResult.INSUFFICIENT_FUNDS);
                return;
            }
            db.transactionThenMain(conn -> {
                if (!PlotDao.extend(conn, plotId, uuid, now, period)) {
                    throw new PlotDenied(RentResult.UNKNOWN_PLOT);
                }
                TransactionDao.log(conn, "PLOT_UPKEEP", uuid, null, null, price, plotId);
                return PlotDao.find(conn, plotId);
            }, updated -> {
                if (updated != null) rentals.put(plotId, updated);
                overdueNotified.remove(plotId);
                TransactionLogger.logNote(player, "MARKET PLOT upkeep " + plotId + " paid to "
                        + (updated != null ? updated.paidUntil() : "?"));
                finish(uuid, callback, RentResult.SUCCESS);
            }, error -> {
                RentResult result = error instanceof PlotDenied denied ? denied.result : RentResult.ERROR;
                refund(player, uuid, price, "plot extend rejected: " + result);
                if (result == RentResult.ERROR) {
                    plugin.getLogger().severe("Plot extend failed for " + player.getName() + " on " + plotId + ": " + error);
                }
                finish(uuid, callback, result);
            });
        });
    }

    /** Voluntary hand-back: the plot is cleared exactly like an eviction, without grace. */
    public void release(Player player, String plotId, Consumer<Boolean> callback) {
        PlotRental rental = rentals.get(plotId);
        if (rental == null || !rental.renterUuid().equals(player.getUniqueId())) {
            callback.accept(false);
            return;
        }
        evict(rental, "released by " + player.getName(), callback);
    }

    // -------------------------------------------------------------------------
    // Upkeep / eviction
    // -------------------------------------------------------------------------

    private void runUpkeep() {
        long now = System.currentTimeMillis();
        long grace = config.plotGraceMs();
        for (PlotRental rental : List.copyOf(rentals.values())) {
            if (rental.isEvictable(now, grace)) {
                evict(rental, "rent lapsed", success -> {
                    if (success) {
                        plugin.getLogger().info("Market plot " + rental.plotId() + " evicted ("
                                + rental.renterName() + ", rent lapsed)");
                    }
                });
            } else if (!rental.isPaid(now)) {
                Player renter = Bukkit.getPlayer(rental.renterUuid());
                // The grace window can span dozens of upkeep ticks; nagging on every one of
                // them reads as a bug, so each renter hears about it once per hour at most.
                if (renter != null && now - overdueNotified.getOrDefault(rental.plotId(), 0L) >= OVERDUE_NOTICE_MS) {
                    overdueNotified.put(rental.plotId(), now);
                    renter.sendMessage(MM.deserialize(config.message("plot-rent-overdue",
                                    "<red>Your market stall's rent is overdue. Pay the Plot Warden or lose it.")
                            .replace("%plot%", displayName(rental.plotId()))));
                }
            }
        }
    }

    /**
     * Clears a plot back to its pristine state: container contents to the renter's
     * stash, vendor NPC destroyed, blocks restored from the snapshot, rental row gone.
     */
    public void evict(PlotRental rental, String reason, Consumer<Boolean> callback) {
        String plotId = rental.plotId();
        PlotRegion region = regions.get(plotId);
        World world = config.world();
        if (!evicting.add(plotId)) {
            callback.accept(false);
            return;
        }
        if (region == null || world == null) {
            // Without the region or the world there is no way to reach the renter's chests,
            // so evicting here would delete the tenancy and leave their goods sealed in a
            // plot nobody owns. Hold the rental instead and let an admin sort the config
            // out — a stall that outstays its rent costs far less than a stall that eats
            // someone's stock.
            evicting.remove(plotId);
            plugin.getLogger().severe("Refusing to evict plot " + plotId + " ("
                    + (world == null ? "market world not loaded" : "region no longer defined in market.yml")
                    + "). The rental is left standing so its contents are not stranded —"
                    + " restore the region definition or load the world, then evict again.");
            callback.accept(false);
            return;
        }

        // Clear-then-commit: pull the goods out first so a crash can only lose, never dupe.
        List<StashItem> harvested = harvestContainers(world, region, rental, reason);
        harvested.addAll(harvestEntities(world, region, rental, reason));
        despawnVendor(rental);
        finishEviction(rental, region, harvested, reason, callback);
    }

    /**
     * Clears the stall's <em>entities</em>: item frames and armour stands with the renter's
     * gear on them, paintings, chest minecarts, and anything dropped on the floor.
     *
     * <p>The block snapshot cannot do this — an item frame is not a block, so a restore
     * leaves it hanging there with its contents for whoever rents the stall next. Contents
     * follow the containers into the stash; the decoration itself is returned as the item
     * it was placed from, so nothing is silently confiscated.
     */
    private List<StashItem> harvestEntities(World world, PlotRegion region, PlotRental rental, String reason) {
        List<StashItem> harvested = new ArrayList<>();
        long now = System.currentTimeMillis();
        // +1 on the max corner because the bounding box is exclusive at the far edge, and a
        // frame hung on the outermost wall sits fractionally inside the last block.
        BoundingBox box = new BoundingBox(region.minX(), region.minY(), region.minZ(),
                region.maxX() + 1.0, region.maxY() + 1.0, region.maxZ() + 1.0);
        for (Entity entity : world.getNearbyEntities(box)) {
            // Citizens tags its NPCs with this metadata whatever entity type they wear —
            // the plot's own vendor is an armour stand on some servers, and despawning it
            // is despawnVendor's job, not a stash row's.
            if (entity.hasMetadata("NPC")) continue;
            List<ItemStack> spoils = new ArrayList<>();
            switch (entity) {
                case Item dropped -> spoils.add(dropped.getItemStack());
                case ItemFrame frame -> {
                    if (!frame.getItem().getType().isAir()) spoils.add(frame.getItem());
                    spoils.add(new ItemStack(frame.getType() == EntityType.GLOW_ITEM_FRAME
                            ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME));
                }
                case ArmorStand stand -> {
                    for (ItemStack piece : stand.getEquipment().getArmorContents()) {
                        if (piece != null && !piece.getType().isAir()) spoils.add(piece);
                    }
                    addIfPresent(spoils, stand.getEquipment().getItemInMainHand());
                    addIfPresent(spoils, stand.getEquipment().getItemInOffHand());
                    spoils.add(new ItemStack(Material.ARMOR_STAND));
                }
                case Painting ignored -> spoils.add(new ItemStack(Material.PAINTING));
                // Display entities are the modern way to dress a stall, and an ItemDisplay
                // holds a real ItemStack. Left alone they survive every eviction and hang
                // there through the next renter's tenancy, still showing the last one's goods.
                case ItemDisplay display -> addIfPresent(spoils, display.getItemStack());
                case BlockDisplay ignored -> {
                }
                case TextDisplay ignored -> {
                }
                case InventoryHolder holder when entity instanceof Vehicle -> {
                    for (ItemStack stack : holder.getInventory().getContents()) {
                        addIfPresent(spoils, stack);
                    }
                    holder.getInventory().clear();
                }
                default -> {
                    // Mobs, NPCs, projectiles and the plot's own vendor are none of our
                    // business — a stall is cleared, not exterminated.
                    continue;
                }
            }
            for (ItemStack stack : spoils) {
                var snapshot = inspector.snapshot(stack);
                harvested.add(new StashItem(UUID.randomUUID(), rental.renterUuid(),
                        stack.serializeAsBytes(), snapshot.material(), snapshot.amount(),
                        snapshot.displayName(), StashItem.SOURCE_EVICTION, region.id(), now));
            }
            entity.remove();
        }
        if (!harvested.isEmpty()) {
            plugin.getLogger().info("Plot " + region.id() + " eviction (" + reason + ") cleared "
                    + harvested.size() + " entity-held stack(s) for " + rental.renterName());
        }
        return harvested;
    }

    private static void addIfPresent(List<ItemStack> into, @Nullable ItemStack stack) {
        if (stack != null && !stack.getType().isAir()) into.add(stack);
    }

    /** Empties every container in the cuboid into stash rows (main thread). */
    private List<StashItem> harvestContainers(World world, PlotRegion region, PlotRental rental, String reason) {
        List<StashItem> harvested = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int y = region.minY(); y <= region.maxY(); y++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    // getState(false) hands back the live tile entity, so clear() empties the
                    // real container instead of a throw-away snapshot copy.
                    if (!(block.getState(false) instanceof Container container)) continue;
                    Inventory inventory = container.getInventory();
                    for (ItemStack stack : inventory.getContents()) {
                        if (stack == null || stack.getType().isAir()) continue;
                        var snapshot = inspector.snapshot(stack);
                        harvested.add(new StashItem(UUID.randomUUID(), rental.renterUuid(),
                                stack.serializeAsBytes(), snapshot.material(), snapshot.amount(),
                                snapshot.displayName(), StashItem.SOURCE_EVICTION, region.id(), now));
                    }
                    inventory.clear();
                }
            }
        }
        if (!harvested.isEmpty()) {
            plugin.getLogger().info("Plot " + region.id() + " eviction (" + reason + ") harvested "
                    + harvested.size() + " stack(s) for " + rental.renterName());
        }
        return harvested;
    }

    /** Commits the stash rows + rental deletion, then replays the block snapshot. */
    private void finishEviction(PlotRental rental, @Nullable PlotRegion region,
                                @Nullable List<StashItem> harvested, String reason, Consumer<Boolean> callback) {
        String plotId = rental.plotId();
        db.transactionThenMain(conn -> {
            if (harvested != null) {
                for (StashItem item : harvested) StashDao.insert(conn, item);
            }
            PlotDao.clearRental(conn, plotId);
            TransactionDao.log(conn, "PLOT_EVICT", rental.renterUuid(), null, null,
                    harvested == null ? 0 : harvested.size(), plotId + " (" + reason + ")");
            return region != null ? PlotDao.snapshot(conn, plotId) : null;
        }, snapshot -> {
            rentals.remove(plotId);
            overdueNotified.remove(plotId);
            // After the commit, before the blocks are replayed: the counters must stop
            // trading the moment the rental is gone, not when the restore finishes.
            if (onEvicted != null) onEvicted.accept(plotId);
            if (snapshot == null || region == null) {
                evicting.remove(plotId);
                callback.accept(true);
                return;
            }
            World world = config.world();
            if (world == null) {
                evicting.remove(plotId);
                callback.accept(true);
                return;
            }
            try {
                PlotSnapshot.restore(plugin, world, region, snapshot, () -> {
                    evicting.remove(plotId);
                    plugin.getLogger().info("Plot " + plotId + " restored to its snapshot");
                    callback.accept(true);
                });
            } catch (Exception e) {
                evicting.remove(plotId);
                plugin.getLogger().severe("Plot " + plotId + " snapshot restore failed: " + e.getMessage());
                callback.accept(true);
            }
        }, error -> {
            evicting.remove(plotId);
            plugin.getLogger().severe("Plot eviction commit failed for " + plotId + ": " + error);
            if (harvested != null && !harvested.isEmpty()) {
                // Clear-then-commit's loss window: name what went missing so staff can restore it.
                for (StashItem item : harvested) {
                    plugin.getLogger().severe("  LOST in plot eviction: " + item.material() + " x"
                            + item.amount() + " owed to " + rental.renterName());
                }
            }
            callback.accept(false);
        });
    }

    // -------------------------------------------------------------------------
    // Admin: snapshots and vendor spots
    // -------------------------------------------------------------------------

    /** Captures (or re-captures) the pristine baseline for {@code region}. Main thread. */
    public void captureSnapshot(PlotRegion region, Consumer<Boolean> callback) {
        World world = config.world();
        if (world == null) {
            callback.accept(false);
            return;
        }
        byte[] blob;
        try {
            blob = PlotSnapshot.capture(world, region);
        } catch (Exception e) {
            plugin.getLogger().severe("Plot snapshot capture failed for " + region.id() + ": " + e.getMessage());
            callback.accept(false);
            return;
        }
        String worldId = WorldUtil.id(world);
        db.transactionThenMain(conn -> {
            PlotDao.upsertSnapshot(conn, region, worldId, blob, System.currentTimeMillis());
            return true;
        }, callback, error -> {
            plugin.getLogger().severe("Plot snapshot insert failed for " + region.id() + ": " + error);
            callback.accept(false);
        });
    }

    /** Spawns the plot's vendor NPC if Citizens is present and the region defines a spot. */
    private void spawnVendor(PlotRegion region, String plotId) {
        World world = config.world();
        Location spot = region.vendorLocation(world);
        if (vendors == null || spot == null) return;
        try {
            int npcId = vendors.spawnPlotVendor(plotId, spot);
            db.submit(conn -> {
                PlotDao.setVendorNpc(conn, plotId, npcId);
                return null;
            });
            PlotRental rental = rentals.get(plotId);
            if (rental != null) {
                rentals.put(plotId, new PlotRental(rental.plotId(), rental.renterUuid(), rental.renterName(),
                        rental.rentedAt(), rental.paidUntil(), npcId));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to spawn vendor NPC for plot " + plotId + ": " + e.getMessage());
        }
    }

    private void despawnVendor(PlotRental rental) {
        if (vendors == null || rental.vendorNpcId() == null) return;
        try {
            vendors.despawnPlotVendor(rental.vendorNpcId());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to despawn vendor NPC " + rental.vendorNpcId()
                    + " for plot " + rental.plotId() + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public String displayName(String plotId) {
        PlotRegion region = regions.get(plotId);
        return region != null ? region.displayName() : plotId;
    }

    private void refund(Player player, UUID uuid, long amount, String reason) {
        if (amount <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean refunded = VaultUtil.deposit(uuid, amount);
            TransactionLogger.logNote(player, "MARKET PLOT refund of " + amount + " coppets ("
                    + reason + ") " + (refunded ? "OK" : "FAILED"));
            if (!refunded) {
                plugin.getLogger().severe("Failed to refund plot rent of " + amount + " coppets to " + uuid);
            }
        });
    }

    /** Expected rent/upkeep rejections decided inside the DB transaction, not failures. */
    private static final class PlotDenied extends MarketDatabase.ControlFlow {
        final RentResult result;

        PlotDenied(RentResult result) {
            super(result.name());
            this.result = result;
        }
    }

    private void finish(UUID uuid, Consumer<RentResult> callback, RentResult result) {
        if (Bukkit.isPrimaryThread()) {
            IN_FLIGHT.remove(uuid);
            callback.accept(result);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                IN_FLIGHT.remove(uuid);
                callback.accept(result);
            });
        }
    }

    /** Drops every single-flight guard. Called on module shutdown — these sets are
     *  static and would otherwise carry a stale lock across a plugin reload. */
    public static void releaseAll() {
        IN_FLIGHT.clear();
    }

}
