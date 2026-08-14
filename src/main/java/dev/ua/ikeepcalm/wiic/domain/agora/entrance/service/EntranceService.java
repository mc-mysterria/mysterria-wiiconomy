package dev.ua.ikeepcalm.wiic.domain.agora.entrance.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketFeedback;
import dev.ua.ikeepcalm.wiic.domain.agora.db.EntranceDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.LandsHook;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketEntrance;
import dev.ua.ikeepcalm.wiic.utils.WorldUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns the entrance registry (in-memory cache over the {@code entrances} table),
 * door placement, and the teleports in and out of the market world.
 *
 * <p>One entrance per Land (DB unique on {@code land_id}); the hub entrance has a
 * null land id. Return points are persisted so a player who logs out inside the
 * market still gets home after a restart.
 *
 * <p>Lands access goes through the nullable {@link LandsHook}: without the Lands
 * plugin, player entrance placement is disabled but the hub entrance still works.
 */
public class EntranceService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final @Nullable LandsHook lands;
    private final MarketFeedback feedback;
    private final ContainmentService containment;

    /** Cache keyed by "world:x:y:z" of the lower door block; maintained on add/remove. */
    private final Map<String, MarketEntrance> byLocation = new ConcurrentHashMap<>();
    private BukkitTask validationTask;

    public EntranceService(WIIC plugin, MarketConfig config, MarketDatabase db,
                           @Nullable LandsHook lands, MarketFeedback feedback,
                           ContainmentService containment) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.lands = lands;
        this.feedback = feedback;
        this.containment = containment;
    }

    public void load() {
        try {
            List<MarketEntrance> all = db.submit(EntranceDao::all).get(10, TimeUnit.SECONDS);
            for (MarketEntrance entrance : all) byLocation.put(key(entrance), entrance);
            plugin.getLogger().info("Loaded " + all.size() + " market entrances");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load market entrances: " + e);
        }
        long interval = config.entranceValidationIntervalTicks();
        validationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::validate, interval, interval);
    }

    public void shutdown() {
        if (validationTask != null) validationTask.cancel();
    }

    public boolean landsAvailable() {
        return lands != null;
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    public enum PlaceResult { SUCCESS, NO_LANDS, NOT_IN_LAND, NOT_TRUSTED, LAND_HAS_ENTRANCE, MARKET_WORLD, BAD_SPOT, ERROR }

    /**
     * Attempts to place a secret entrance at {@code base} (the block the door will
     * stand on) for {@code player}. Main thread; the DB insert is async, the door
     * blocks are only set after it commits.
     */
    public void place(Player player, Block base, Consumer<PlaceResult> callback) {
        if (config.isMarketWorld(player.getWorld())) {
            callback.accept(PlaceResult.MARKET_WORLD);
            return;
        }
        if (lands == null) {
            callback.accept(PlaceResult.NO_LANDS);
            return;
        }
        Block lower = base.getRelative(BlockFace.UP);
        Block upper = lower.getRelative(BlockFace.UP);
        if (!lower.getType().isAir() || !upper.getType().isAir()) {
            callback.accept(PlaceResult.BAD_SPOT);
            return;
        }
        Location doorLoc = lower.getLocation();
        LandsHook.LandInfo land = lands.landAt(doorLoc);
        if (land == null) {
            callback.accept(PlaceResult.NOT_IN_LAND);
            return;
        }
        if (!lands.isTrusted(player, doorLoc)) {
            callback.accept(PlaceResult.NOT_TRUSTED);
            return;
        }

        MarketEntrance entrance = new MarketEntrance(UUID.randomUUID(), land.id(),
                WorldUtil.id(doorLoc.getWorld()), doorLoc.getBlockX(), doorLoc.getBlockY(), doorLoc.getBlockZ(),
                player.getUniqueId(), System.currentTimeMillis());

        db.transactionThenMain(conn -> {
            if (EntranceDao.byLandId(conn, land.id()) != null) return false;
            EntranceDao.insert(conn, entrance);
            return true;
        }, inserted -> {
            if (!inserted) {
                callback.accept(PlaceResult.LAND_HAS_ENTRANCE);
                return;
            }
            buildDoor(lower, player);
            byLocation.put(key(entrance), entrance);
            callback.accept(PlaceResult.SUCCESS);
        }, error -> {
            plugin.getLogger().severe("Entrance insert failed: " + error);
            callback.accept(PlaceResult.ERROR);
        });
    }

    private void buildDoor(Block lower, Player player) {
        Material doorMaterial = Material.matchMaterial(
                config.raw().getString("entrance.door-material", "CRIMSON_DOOR"));
        if (doorMaterial == null || !(doorMaterial.createBlockData() instanceof Door)) {
            doorMaterial = Material.CRIMSON_DOOR;
        }
        BlockFace facing = player.getFacing().getOppositeFace();

        Door lowerData = (Door) doorMaterial.createBlockData();
        lowerData.setFacing(facing);
        lowerData.setHalf(Bisected.Half.BOTTOM);
        lower.setBlockData(lowerData, false);

        Door upperData = (Door) doorMaterial.createBlockData();
        upperData.setFacing(facing);
        upperData.setHalf(Bisected.Half.TOP);
        lower.getRelative(BlockFace.UP).setBlockData(upperData, false);
    }

    /** Registers the admin hub entrance ({@code land_id = null}) at {@code doorBlock}. */
    public void registerHub(Player admin, Block doorBlock, Consumer<Boolean> callback) {
        Block lower = resolveLowerDoorBlock(doorBlock);
        Location loc = lower.getLocation();
        MarketEntrance entrance = new MarketEntrance(UUID.randomUUID(), null,
                WorldUtil.id(loc.getWorld()), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                admin.getUniqueId(), System.currentTimeMillis());
        db.transactionThenMain(conn -> {
            EntranceDao.insert(conn, entrance);
            return true;
        }, inserted -> {
            byLocation.put(key(entrance), entrance);
            callback.accept(true);
        }, error -> {
            plugin.getLogger().severe("Hub entrance insert failed: " + error);
            callback.accept(false);
        });
    }

    // -------------------------------------------------------------------------
    // Lookup / removal
    // -------------------------------------------------------------------------

    /**
     * Registered entrance at the clicked block (either door half), or null. Main-thread cheap.
     *
     * <p>A registration whose block has become air is a ghost — the door was destroyed by
     * something that never reached {@code BlockBreakEvent} (explosion, flow, support gone).
     * Returning it would let anyone re-place a block there and walk into the market, so the
     * ghost is deregistered on the spot instead.
     */
    public @Nullable MarketEntrance entranceAt(Block block) {
        if (byLocation.isEmpty()) return null;
        Block lower = resolveLowerDoorBlock(block);
        MarketEntrance entrance = byLocation.get(key(lower.getLocation()));
        if (entrance == null) return null;
        if (block.getType().isAir()) {
            remove(entrance, "door block is gone");
            return null;
        }
        return entrance;
    }

    /** Cheap gate for the block-event handlers: nothing registered, nothing to guard. */
    public boolean isEmpty() {
        return byLocation.isEmpty();
    }

    public void remove(MarketEntrance entrance, String reason) {
        byLocation.remove(key(entrance));
        db.submit(conn -> {
            EntranceDao.delete(conn, entrance.id());
            return null;
        });
        plugin.getLogger().info("Market entrance " + entrance.id() + " removed (" + reason + ")");
    }

    public List<MarketEntrance> all() {
        return List.copyOf(byLocation.values());
    }

    /**
     * The lower half of a door, so both halves resolve to one registered position.
     *
     * <p>The material check comes first on purpose: {@code getBlockData()} allocates, and
     * this runs from liquid-flow and piston handlers that fire thousands of times a second
     * on blocks that are never doors.
     */
    public static Block resolveLowerDoorBlock(Block block) {
        if (!Tag.DOORS.isTagged(block.getType())) return block;
        if (block.getBlockData() instanceof Door door && door.getHalf() == Bisected.Half.TOP) {
            return block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    // -------------------------------------------------------------------------
    // Teleports
    // -------------------------------------------------------------------------

    /** Sends {@code player} into the market, persisting their current spot as the return point. */
    public void enter(Player player, MarketEntrance entrance) {
        Location arrival = config.arrival();
        if (arrival == null) {
            player.sendMessage(MM.deserialize(config.message("market-unavailable",
                    "<red>The passage is sealed... (market world unavailable)")));
            return;
        }
        Location from = player.getLocation().clone();
        db.submit(conn -> {
            EntranceDao.upsertReturnPoint(conn, player.getUniqueId(), serialize(from), entrance.id());
            return null;
        });
        // Staged descent: the door reacts where it stands (so anyone watching sees somebody
        // step into a wall and not come out), the traveller's world goes dark, and only then
        // does the ground change under them.
        Location door = entrance.location();
        int descent = config.entranceDescentTicks();
        feedback.descentBegins(player, door != null ? door : from, descent);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // The containment refuses every crossing it did not arrange — including this one,
            // unless it is told first. Sanctioned immediately before the call so nothing can
            // slip in between and spend it.
            containment.sanction(player);
            player.teleportAsync(arrival).thenAccept(ok -> {
                if (!ok) {
                    feedback.descentAborted(player);
                    return;
                }
                feedback.arrived(player);
                player.sendMessage(MM.deserialize(config.message("entered-market",
                        "<dark_gray>The door closes behind you. Welcome to the Underground Market.")));
            });
        }, descent);
    }

    /** Sends {@code player} back to their stored return point (fallbacks: main world spawn). */
    public void exit(Player player) {
        db.submitThenMain(conn -> EntranceDao.returnPoint(conn, player.getUniqueId()), serialized -> {
            Location target = serialized != null ? deserialize(serialized) : null;
            if (target == null) {
                World fallback = Bukkit.getWorlds().getFirst();
                target = fallback.getSpawnLocation();
            }
            Location destination = target;
            int ascent = config.entranceDescentTicks() / 2;
            feedback.departing(player, ascent);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                // Drop the return point only once they are actually out: a teleport that
                // fails (chunk load refused, another plugin cancelling) must leave them a
                // way home.
                containment.sanction(player);
                player.teleportAsync(destination).thenAccept(ok -> {
                    if (!ok) {
                        feedback.descentAborted(player);
                        return;
                    }
                    db.submit(conn -> {
                        EntranceDao.deleteReturnPoint(conn, player.getUniqueId());
                        return null;
                    });
                    feedback.departed(player);
                    player.sendMessage(MM.deserialize(config.message("exited-market",
                            "<dark_gray>You slip back into the daylight.")));
                });
            }, ascent);
        }, error -> plugin.getLogger().severe("Return-point lookup failed for " + player.getName() + ": " + error));
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Periodic registry sweep: drops entrances whose door block no longer exists and (for
     * land entrances) whose Land no longer exists or no longer claims the spot. Only looks
     * at already-loaded chunks — validating an entrance is never worth loading terrain.
     */
    private void validate() {
        for (MarketEntrance entrance : byLocation.values()) {
            Location loc = entrance.location();
            if (loc == null) continue; // world not loaded; leave it alone
            if (loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)
                    && loc.getBlock().getType().isAir()) {
                remove(entrance, "door block is gone");
                continue;
            }
            if (lands == null || entrance.isHub()) continue;
            if (!lands.landStillClaims(entrance.landId(), loc)) {
                remove(entrance, "land no longer exists or moved");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Rows written before world ids were canonicalised still bucket here correctly. */
    private static String key(MarketEntrance entrance) {
        return WorldUtil.canonical(entrance.world())
                + ":" + entrance.x() + ":" + entrance.y() + ":" + entrance.z();
    }

    private static String key(Location loc) {
        return WorldUtil.id(loc.getWorld()) + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private static String serialize(Location loc) {
        return WorldUtil.id(loc.getWorld()) + ";" + loc.getX() + ";" + loc.getY() + ";" + loc.getZ()
                + ";" + loc.getYaw() + ";" + loc.getPitch();
    }

    private static @Nullable Location deserialize(String serialized) {
        try {
            String[] parts = serialized.split(";");
            World world = WorldUtil.resolve(parts[0]);
            if (world == null) return null;
            return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (Exception e) {
            return null;
        }
    }
}
