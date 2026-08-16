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

    /** Consecutive "land no longer claims this" answers tolerated before a door comes down. */
    private static final int UNCLAIMED_STRIKES = 3;

    /** Cache keyed by "world:x:y:z" of the lower door block; maintained on add/remove. */
    private final Map<String, MarketEntrance> byLocation = new ConcurrentHashMap<>();
    /** Per-entrance strike count for {@link #validate()}; cleared the moment Lands agrees again. */
    private final Map<UUID, Integer> unclaimedStrikes = new ConcurrentHashMap<>();
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
            List<MarketEntrance> all = db.awaitLoad("entrance", EntranceDao::all);
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
        unclaimedStrikes.remove(entrance.id());
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
     * Whether {@code player} may take this door down.
     *
     * <p>The land's own people can, always. A door whose only removal is "unclaim the
     * chunk, wait for the sweep, claim it again" is a door nobody can move, and moving a
     * secret entrance is the most ordinary thing an owner will ever want to do with one.
     * The hub is the exception: it is the server's public way in, so it stays admin-only
     * however {@code allow-break} is set.
     */
    public boolean canBreak(Player player, MarketEntrance entrance) {
        if (player.hasPermission(MarketConfig.ADMIN_PERMISSION)) return true;
        if (entrance.isHub()) return false;
        if (config.entranceAllowBreak()) return true;
        if (player.getUniqueId().equals(entrance.createdBy())) return true;
        Location door = entrance.location();
        return lands != null && door != null && lands.isTrusted(player, door);
    }

    /**
     * A standing spot in the doorway of {@code entranceId}, or null when that door is
     * gone. Centred in the block so the arrival isn't wedged against the frame.
     */
    private @Nullable Location doorwayOf(@Nullable UUID entranceId) {
        if (entranceId == null) return null;
        for (MarketEntrance entrance : byLocation.values()) {
            if (!entrance.id().equals(entranceId)) continue;
            Location loc = entrance.location();
            if (loc == null) return null;
            Location doorway = loc.clone().add(0.5, 0, 0.5);
            return isSafe(doorway) ? doorway : null;
        }
        return null;
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
        if (config.entranceRequireTrust() && lands != null && !entrance.isHub()) {
            Location door = entrance.location();
            if (door != null && !lands.isTrusted(player, door)) {
                player.sendMessage(MM.deserialize(config.message("entrance-not-trusted",
                        "<red>You are not trusted in this land.")));
                return;
            }
        }
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

        World startWorld = from.getWorld();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Only descend if they are still standing where they knocked. Dying, respawning
            // or being teleported away during the descent used to drag them underground from
            // wherever they had ended up.
            if (player.isDead() || !startWorld.equals(player.getWorld())
                    || player.getLocation().distanceSquared(from) > 64) {
                feedback.descentAborted(player);
                return;
            }
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
        db.submitThenMain(conn -> EntranceDao.returnPoint(conn, player.getUniqueId()), stored -> {
            Location target = stored != null ? deserialize(stored.location()) : null;
            boolean strayed = target == null || !isSafe(target);
            if (strayed && stored != null) {
                // Before giving up on them entirely: the door they came in through is still
                // the right side of the world, and standing in an open doorway is safe by
                // definition. Only a door that has since been destroyed sends them to spawn.
                Location door = doorwayOf(stored.entranceId());
                if (door != null) {
                    target = door;
                    strayed = false;
                }
            }
            if (target == null || strayed) {
                // The way they came in is gone or has become lethal (world unloaded, lava
                // flowed in, terrain rebuilt). Say so — silently landing them somewhere else
                // under the usual "you slip back into the daylight" reads as a teleport bug.
                World fallback = Bukkit.getWorlds().getFirst();
                target = fallback.getSpawnLocation();
                strayed = true;
            }
            Location destination = target;
            boolean astray = strayed;
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
                    player.sendMessage(MM.deserialize(astray
                            ? config.message("exited-market-astray",
                            "<yellow>The passage back has collapsed. You surface somewhere else entirely.")
                            : config.message("exited-market",
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
            boolean chunkLoaded = loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            if (chunkLoaded && loc.getBlock().getType().isAir()) {
                remove(entrance, "door block is gone");
                continue;
            }
            if (lands == null || entrance.isHub()) continue;
            // The same rule the air check above follows, and it was missing here: an
            // entrance in an unloaded chunk is not evidence of anything. Asking Lands about
            // terrain nobody has loaded — and then demolishing a door on the answer — is how
            // entrances went missing while their owners were away.
            if (!chunkLoaded) continue;
            if (lands.landStillClaims(entrance.landId(), loc)) {
                unclaimedStrikes.remove(entrance.id());
                continue;
            }
            // One "not claimed" is not proof. Lands loads its own data asynchronously and
            // can answer for a land it hasn't finished reading; demolishing a door on a
            // single answer is unrecoverable, and waiting costs nothing but time.
            if (unclaimedStrikes.merge(entrance.id(), 1, Integer::sum) < UNCLAIMED_STRIKES) continue;
            // Take the door down with the registration. Leaving it standing turns a
            // secret entrance into an ordinary door that simply stops working, with
            // nothing to tell its owner why.
            clearDoor(loc);
            remove(entrance, "land no longer exists or moved");
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

    /** Removes both halves of a door the registry is giving up on. */
    private static void clearDoor(Location loc) {
        Block lower = loc.getBlock();
        Block upper = lower.getRelative(BlockFace.UP);
        if (upper.getBlockData() instanceof Door) upper.setType(Material.AIR, false);
        if (lower.getBlockData() instanceof Door) lower.setType(Material.AIR, false);
    }

    /**
     * Whether a stored return point is still somewhere a player can be put down. Chunks are
     * loaded to answer this — a return point is worth the load, and refusing to check would
     * mean dropping people into whatever the world became while they were away.
     */
    private static boolean isSafe(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        if (loc.getY() < world.getMinHeight() || loc.getY() > world.getMaxHeight()) return false;
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        if (wouldTrap(feet) || wouldTrap(head)) return false;
        return !isHarmful(feet) && !isHarmful(head) && !isHarmful(feet.getRelative(BlockFace.DOWN));
    }

    /**
     * Whether standing in this block would bury a player.
     *
     * <p>Not {@code isSolid()}: a slab, a stair, a path block, soul sand and an open door
     * are all "solid", and a player standing on any of them has their own feet inside that
     * very block — so the spot they were standing on when they walked into the market read
     * back as a wall, and they were posted to the world spawn instead. Occlusion is the
     * question actually being asked, and it is false for everything a player can stand in.
     */
    private static boolean wouldTrap(Block block) {
        return block.getType().isOccluding();
    }

    private static boolean isHarmful(Block block) {
        return switch (block.getType()) {
            case LAVA, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE, MAGMA_BLOCK, WITHER_ROSE, SWEET_BERRY_BUSH,
                 POINTED_DRIPSTONE, CACTUS -> true;
            default -> false;
        };
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
