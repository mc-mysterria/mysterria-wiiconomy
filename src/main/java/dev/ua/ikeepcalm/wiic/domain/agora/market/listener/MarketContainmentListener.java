package dev.ua.ikeepcalm.wiic.domain.agora.market.listener;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.ContainmentService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * The event wall around {@link ContainmentService} — every way a player can cross the
 * market's threshold or leave its envelope, refused at the source.
 *
 * <p>The listeners fall into three groups:
 * <ul>
 *   <li><b>The threshold.</b> {@code PlayerTeleportEvent} catches every teleport the
 *       server API knows about, whatever plugin or ability fired it, including the
 *       vanilla causes (pearl, chorus, gateway, spectate, portal). {@code
 *       PlayerPortalEvent} needs its own handler despite extending it — Bukkit gives it a
 *       separate handler list, so a {@code PlayerTeleportEvent} listener never sees one.</li>
 *   <li><b>The envelope.</b> Movement out of bounds is cancelled outright; the service's
 *       patrol catches anything that got out without moving.</li>
 *   <li><b>The ways round it.</b> Vehicles, elytra, spectator mode and respawn points are
 *       each a way to be somewhere the first two groups did not put you.</li>
 * </ul>
 *
 * <p>Pearls and chorus fruit are stopped at the throw and the bite as well as at the
 * teleport: the teleport gate alone would refuse the trip <em>after</em> the item was
 * spent, which reads as the market eating your inventory.
 *
 * <p>Every handler's first act is the world check, so the cost outside the market world
 * is one reference comparison.
 */
public class MarketContainmentListener implements Listener {

    private final MarketConfig config;
    private final ContainmentService containment;

    public MarketContainmentListener(MarketConfig config, ContainmentService containment) {
        this.config = config;
        this.containment = containment;
    }

    // -------------------------------------------------------------------------
    // The threshold
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        containment.screenTeleport(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        containment.screenTeleport(event);
    }

    /**
     * Mounts, pets and dropped items don't get to portal in or out of the market either.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!containment.enabled()) return;
        var to = event.getTo();
        if (config.isMarketWorld(event.getEntity().getWorld())
                || (to != null && config.isMarketWorld(to.getWorld()))) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // The envelope
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!config.isMarketWorld(event.getPlayer().getWorld())) return;
        if (!containment.enabled() || !event.hasChangedBlock()) return;
        containment.screenMove(event);
    }

    // -------------------------------------------------------------------------
    // Presence
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        containment.verifyPresence(event.getPlayer());
    }

    /**
     * The net under the teleport gate: a player who materialises in the market world
     * without a teleport event to screen still has to account for how they got there.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        containment.verifyPresence(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        containment.forget(event.getPlayer());
    }

    /**
     * A respawn point underground would be a permanent back door.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (containment.respawnAllowed(event.getPlayer(), event.getRespawnLocation())) return;
        event.setRespawnLocation(Bukkit.getWorlds().getFirst().getSpawnLocation());
    }

    // -------------------------------------------------------------------------
    // The ways round it
    // -------------------------------------------------------------------------

    /**
     * Refuses the throw rather than the arrival, so the pearl stays in the player's hand.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(PlayerLaunchProjectileEvent event) {
        if (!config.blockEnderPearls() || !containment.enabled()) return;
        if (!(event.getProjectile() instanceof EnderPearl)) return;
        Player player = event.getPlayer();
        if (!config.isMarketWorld(player.getWorld()) || containment.bypasses(player)) return;
        event.setShouldConsume(false);
        containment.refuse(event, player, "containment-no-pearl",
                "<dark_gray><i>The pearl goes dead in your hand. Nothing here answers it.</i>");
    }

    /**
     * Dispensers and plugin-spawned pearls, which never reach the player launch event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!config.blockEnderPearls() || !containment.enabled()) return;
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!config.isMarketWorld(pearl.getWorld())) return;
        ProjectileSource shooter = pearl.getShooter();
        if (shooter instanceof Player player && containment.bypasses(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!config.blockChorusFruit() || !containment.enabled()) return;
        if (event.getItem().getType() != Material.CHORUS_FRUIT) return;
        Player player = event.getPlayer();
        if (!config.isMarketWorld(player.getWorld()) || containment.bypasses(player)) return;
        containment.refuse(event, player, "containment-no-chorus",
                "<dark_gray><i>The fruit tastes of nothing. Wherever it wanted to send you, it cannot reach.</i>");
    }

    /**
     * Nobody flies out over the walls.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!config.blockElytra() || !containment.enabled()) return;
        if (!event.isGliding() || !(event.getEntity() instanceof Player player)) return;
        if (!config.isMarketWorld(player.getWorld()) || containment.bypasses(player)) return;
        event.setCancelled(true);
    }

    /**
     * Spectator is a licence to walk through the market's walls.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (!containment.enabled() || event.getNewGameMode() != GameMode.SPECTATOR) return;
        Player player = event.getPlayer();
        if (!config.isMarketWorld(player.getWorld()) || containment.bypasses(player)) return;
        containment.refuse(event, player, "containment-no-spectator",
                "<dark_gray><i>Down here you are as solid as anyone else.</i>");
    }

    /**
     * Boats, minecarts and mounts. A ridden entity moves by rules of its own and would
     * carry a player through the containment on the vehicle's terms, not the player's.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!config.blockVehicles() || !containment.enabled()) return;
        if (!(event.getEntered() instanceof Player player)) return;
        if (!config.isMarketWorld(player.getWorld()) || containment.bypasses(player)) return;
        containment.refuse(event, player, "containment-no-vehicle",
                "<dark_gray><i>You go through the market on your own two feet.</i>");
    }
}
