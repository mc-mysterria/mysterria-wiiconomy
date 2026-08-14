package dev.ua.ikeepcalm.wiic.domain.agora.entrance.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketFeedback;
import dev.ua.ikeepcalm.wiic.domain.agora.db.EntranceDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketBounds;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the Underground Market sealed: the door is the only way in and the only way out.
 *
 * <p>The market only works as a place if getting there means something. A server with
 * warps, homes, pearls, blinks, phase abilities and party teleports has dozens of ways to
 * short-circuit that, and enumerating them one plugin at a time is a losing game. So this
 * class works the other way round — <b>everything is refused unless WIIC itself arranged
 * it</b>:
 *
 * <ol>
 *   <li>{@link #sanction} is called immediately before each of {@link EntranceService}'s
 *       own teleports, and is consumed by the very next teleport that player makes. Any
 *       teleport crossing the market's threshold without one is cancelled, whatever fired
 *       it and whichever plugin owns it.</li>
 *   <li>{@link MarketBounds} is the physical envelope. Movement that would leave it is
 *       cancelled; anything that lands outside it anyway — a vehicle, a phase ability, a
 *       plugin writing positions without events — is put back by {@link #patrol()}.</li>
 *   <li>Arriving in the market world by a route that fired no teleport event at all is
 *       caught on world change and on join, and checked against the return-point table:
 *       a visitor WIIC has no way home for was never let in, and is sent to spawn.</li>
 * </ol>
 *
 * <p>Everything here is a no-op outside the market world, and every entry point's first
 * act is the world-reference comparison in {@link MarketConfig#isMarketWorld}. The one
 * genuinely hot path — {@code PlayerMoveEvent} — never runs at all for the other worlds.
 *
 * <p>Holders of {@link #BYPASS_PERMISSION} (and market admins) pass through all of it.
 */
public class ContainmentService {

    /** Moves in and out of the market freely. Implied by {@code wiic.market.admin}. */
    public static final String BYPASS_PERMISSION = "wiic.market.bypass";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /**
     * How long a sanction stays good for. Generous compared to the microseconds between
     * sanctioning and the teleport firing, but short enough that a teleport cancelled by
     * some other plugin can't leave a usable one lying around.
     */
    private static final long SANCTION_TTL_MS = 5_000;

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final MarketFeedback feedback;

    /** Player → expiry of their pending WIIC-authorised teleport. */
    private final Map<UUID, Long> sanctions = new ConcurrentHashMap<>();

    /** Player → when they were last told no, so a looping ability can't spam chat. */
    private final Map<UUID, Long> refusedAt = new ConcurrentHashMap<>();

    /** Visitors WIIC knows it admitted, so an unexplained arrival stands out. */
    private final Set<UUID> admitted = ConcurrentHashMap.newKeySet();

    /** Guards the rescue path against a misconfigured arrival point outside the bounds. */
    private boolean arrivalWarned;

    private @Nullable BukkitTask patrolTask;

    public ContainmentService(WIIC plugin, MarketConfig config, MarketDatabase db, MarketFeedback feedback) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.feedback = feedback;
    }

    public void start() {
        if (!config.containmentEnabled()) {
            plugin.getLogger().warning("Market containment is disabled in market.yml — "
                    + "any teleport plugin can now move players in and out of the market world");
            return;
        }
        MarketBounds bounds = config.bounds();
        if (bounds == null) {
            plugin.getLogger().warning("Market containment has no bounds defined — only the void floor is "
                    + "enforced. Define the envelope with /wiicmarket bounds set (plot wand selection).");
        } else {
            plugin.getLogger().info("Market containment active (bounds " + bounds.describe() + ")");
            Location arrival = config.arrival();
            if (arrival != null && !bounds.contains(arrival)) {
                plugin.getLogger().severe("Market arrival point is OUTSIDE containment.bounds — "
                        + "players returned to it would be pushed straight back out. Fix arrival or bounds.");
            }
        }
        long interval = config.containmentPatrolTicks();
        patrolTask = Bukkit.getScheduler().runTaskTimer(plugin, this::patrol, interval, interval);
    }

    public void shutdown() {
        if (patrolTask != null) patrolTask.cancel();
    }

    public boolean enabled() {
        return config.containmentEnabled();
    }

    public boolean bypasses(Player player) {
        return player.hasPermission(BYPASS_PERMISSION) || player.hasPermission(MarketConfig.ADMIN_PERMISSION);
    }

    // -------------------------------------------------------------------------
    // Sanctions — WIIC's own teleports
    // -------------------------------------------------------------------------

    /**
     * Marks the player's next teleport as WIIC's own. Call this immediately before the
     * teleport itself; it is single-use and expires in {@value #SANCTION_TTL_MS}ms.
     */
    public void sanction(Player player) {
        sanctions.put(player.getUniqueId(), System.currentTimeMillis() + SANCTION_TTL_MS);
    }

    private boolean consumeSanction(Player player) {
        Long expiry = sanctions.remove(player.getUniqueId());
        return expiry != null && expiry >= System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // The threshold
    // -------------------------------------------------------------------------

    /**
     * The gate every teleport touching the market world passes through. Shared by
     * {@code PlayerTeleportEvent} and {@code PlayerPortalEvent}, which carry separate
     * handler lists despite one extending the other.
     */
    public void screenTeleport(PlayerTeleportEvent event) {
        if (!config.containmentEnabled()) return;
        Location from = event.getFrom();
        // PlayerPortalEvent hands us a null destination when the server found nowhere to
        // put the player. Underground that is still an attempt to leave, so it is refused
        // on the strength of where they are rather than where they were going.
        Location to = event.getTo();
        boolean entering = to != null && config.isMarketWorld(to.getWorld());
        boolean leaving = config.isMarketWorld(from.getWorld());
        if (!entering && !leaving) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean sanctioned = consumeSanction(player);
        if (sanctioned || bypasses(player)) {
            if (entering) admitted.add(id); else admitted.remove(id);
            return;
        }

        if (entering && !leaving) {
            refuse(event, player, "containment-no-entry",
                    "<dark_gray><i>Something turns you aside. There is only one way down.</i>");
            return;
        }
        if (leaving && !entering) {
            refuse(event, player, "containment-no-exit",
                    "<dark_gray><i>The dark closes around you. The market lets you leave by the door, or not at all.</i>");
            return;
        }
        // Both ends underground: a blink, a pearl, a spectator jump. Refused unless the
        // cause is one of the vanilla mechanics nobody can abuse (dismounting, leaving a
        // bed), and refused outright if it would land outside the envelope.
        if (!config.containmentAllowedInternalCauses().contains(event.getCause().name())) {
            refuse(event, player, "containment-no-blink",
                    "<dark_gray><i>Whatever you reached for finds no purchase down here.</i>");
            return;
        }
        MarketBounds bounds = config.bounds();
        if (bounds != null && !bounds.contains(to)) {
            refuse(event, player, "containment-no-blink",
                    "<dark_gray><i>Whatever you reached for finds no purchase down here.</i>");
        }
    }

    /**
     * Bounds enforcement on foot. Only ever called for players already in the market world,
     * and only when they crossed a block boundary.
     */
    public void screenMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        MarketBounds bounds = config.bounds();
        // The overwhelmingly common case is somebody walking around inside the market, so
        // it is settled before the permission lookup: this runs on every block a visitor
        // crosses, and a bypass check per step would be the only real cost in the class.
        boolean inside = bounds != null
                ? bounds.contains(to)
                : to.getY() >= voidFloor(to.getWorld());
        if (inside) return;

        Player player = event.getPlayer();
        if (bypasses(player)) return;
        if (bounds == null) {
            event.setCancelled(true);
            rescue(player, "fell below the market floor");
            return;
        }
        if (bounds.contains(event.getFrom())) {
            // Cancelling puts them back where they started, which is inside — no teleport,
            // no screen flicker, just a wall they can't walk through.
            event.setCancelled(true);
            notifyRefusal(player, "containment-edge",
                    "<dark_gray><i>The dark past this point does not want you in it.</i>");
            return;
        }
        // Already out and still moving: cancelling alone would only pin them there, so the
        // move is stopped and the rescue teleport takes over.
        event.setCancelled(true);
        rescue(player, "moved outside the market bounds");
    }

    /**
     * A player has turned up in the market world by some route that fired no teleport we
     * could screen. If WIIC has a way home on record for them they were let in properly
     * (a login inside the market, most often); if not, they were never admitted.
     */
    public void verifyPresence(Player player) {
        if (!config.containmentEnabled()) return;
        if (!config.isMarketWorld(player.getWorld())) {
            admitted.remove(player.getUniqueId());
            return;
        }
        if (bypasses(player)) {
            admitted.add(player.getUniqueId());
            return;
        }
        if (admitted.contains(player.getUniqueId())) {
            enforceBounds(player);
            return;
        }
        UUID id = player.getUniqueId();
        db.submitThenMain(conn -> EntranceDao.returnPoint(conn, id) != null, hasWayHome -> {
            if (!player.isOnline() || !config.isMarketWorld(player.getWorld())) return;
            if (hasWayHome) {
                admitted.add(id);
                enforceBounds(player);
                return;
            }
            expel(player, "arrived in the market with no entry on record");
        }, error -> {
            // Fail open: a database hiccup must not start throwing players out of a world
            // they are legitimately standing in.
            plugin.getLogger().warning("Containment could not verify " + player.getName()
                    + "'s presence in the market: " + error);
            admitted.add(id);
        });
    }

    public void forget(Player player) {
        UUID id = player.getUniqueId();
        admitted.remove(id);
        sanctions.remove(id);
        refusedAt.remove(id);
    }

    /** Whether a respawn at {@code location} would smuggle the player back underground. */
    public boolean respawnAllowed(Player player, Location location) {
        if (!config.containmentEnabled()) return true;
        if (!config.isMarketWorld(location.getWorld())) return true;
        return bypasses(player) || admitted.contains(player.getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Corrections
    // -------------------------------------------------------------------------

    /** Puts a player who ended up outside the envelope back at the arrival point. */
    public void rescue(Player player, String reason) {
        Location arrival = config.arrival();
        MarketBounds bounds = config.bounds();
        if (arrival == null || (bounds != null && !bounds.contains(arrival))) {
            if (!arrivalWarned) {
                arrivalWarned = true;
                plugin.getLogger().severe("Market containment cannot return players inside: the arrival point is "
                        + (arrival == null ? "not configured" : "outside containment.bounds")
                        + ". Sending them out of the world instead.");
            }
            expel(player, reason);
            return;
        }
        plugin.getLogger().info("Market containment returned " + player.getName()
                + " to the arrival point (" + reason + ")");
        sanction(player);
        player.teleportAsync(arrival);
        notifyRefusal(player, "containment-returned",
                "<dark_gray><i>The dark folds over, and you are standing where you started.</i>");
    }

    /**
     * Removes a player from the market world entirely. Used when there is nowhere safe to
     * put them underground, or when they were never admitted in the first place.
     */
    public void expel(Player player, String reason) {
        World fallback = Bukkit.getWorlds().getFirst();
        Location spawn = fallback.getSpawnLocation();
        plugin.getLogger().warning("Market containment removed " + player.getName()
                + " from the market world (" + reason + ")");
        admitted.remove(player.getUniqueId());
        sanction(player);
        player.teleportAsync(spawn);
        player.sendMessage(MM.deserialize(config.message("containment-expelled",
                "<dark_gray><i>The passage rejects you, and the daylight takes you back.</i>")));
    }

    /**
     * The sweep that makes the rest of it fool-proof. Whatever moved a player — a vehicle,
     * an ability writing positions straight into the entity, a chunk of terrain that fell
     * away — a player standing outside the envelope is corrected within one interval.
     */
    private void patrol() {
        World world = config.world();
        if (world == null) return;
        MarketBounds bounds = config.bounds();
        double floor = voidFloor(world);
        for (Player player : world.getPlayers()) {
            if (bypasses(player)) continue;
            Location loc = player.getLocation();
            boolean inside = bounds != null ? bounds.contains(loc) : loc.getY() >= floor;
            if (!inside) rescue(player, "found outside the market bounds");
        }
    }

    private void enforceBounds(Player player) {
        MarketBounds bounds = config.bounds();
        Location loc = player.getLocation();
        boolean inside = bounds != null ? bounds.contains(loc) : loc.getY() >= voidFloor(loc.getWorld());
        if (!inside) rescue(player, "logged in outside the market bounds");
    }

    /** One block above the world's floor — below this there is nothing left to stand on. */
    private static double voidFloor(@Nullable World world) {
        return world == null ? 0 : world.getMinHeight() + 1;
    }

    // -------------------------------------------------------------------------
    // Saying no
    // -------------------------------------------------------------------------

    /** Cancels {@code event} and tells the player why, at most once per cooldown. */
    public void refuse(Cancellable event, Player player, String key, String def) {
        event.setCancelled(true);
        notifyRefusal(player, key, def);
    }

    private void notifyRefusal(Player player, String key, String def) {
        long now = System.currentTimeMillis();
        Long last = refusedAt.get(player.getUniqueId());
        if (last != null && now - last < config.containmentMessageCooldownMs()) return;
        refusedAt.put(player.getUniqueId(), now);
        player.sendMessage(MM.deserialize(config.message(key, def)));
        feedback.refused(player);
    }
}
