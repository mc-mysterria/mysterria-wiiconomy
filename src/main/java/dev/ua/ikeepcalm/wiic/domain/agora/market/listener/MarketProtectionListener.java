package dev.ua.ikeepcalm.wiic.domain.agora.market.listener;

import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRegion;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/**
 * Keeps the market world a fully safe zone without assuming WorldGuard: no player
 * damage of any kind, no explosions, no hostile natural spawns, and no building or
 * container access except by admins and a plot's own renter
 * ({@link PlotService#canBuild}). Every handler's first check is the world gate —
 * zero cost anywhere else.
 */
public class MarketProtectionListener implements Listener {

    private final MarketConfig config;
    private final PlotService plots;

    public MarketProtectionListener(MarketConfig config, PlotService plots) {
        this.config = config;
        this.plots = plots;
    }

    private boolean canBuild(Player player, Location location) {
        return plots.canBuild(player, location);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        if (!canBuild(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        if (!canBuild(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        if (!canBuild(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        if (!canBuild(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    /**
     * Containers inside a plot belong to its renter — everyone else's right-click is
     * refused, so a stall's stock can't be emptied by passers-by (and so eviction can
     * hand the contents back to the right player).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !config.isMarketWorld(block.getWorld())) return;
        if (!(block.getState(false) instanceof Container)) return;
        if (!canBuild(event.getPlayer(), block.getLocation())) event.setCancelled(true);
    }

    /**
     * No player takes damage in the market, from anything.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!config.isMarketWorld(event.getEntity().getWorld())) return;
        event.setCancelled(true);
    }

    /**
     * No player deals damage in the market either (covers pets/NPC victims, walks projectiles).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!config.isMarketWorld(event.getEntity().getWorld())) return;
        var damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player) {
            event.setCancelled(true);
        } else if (damager instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!config.isMarketWorld(event.getEntity().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        event.setCancelled(true);
    }

    /**
     * No hostile mob gets to exist here, however it was summoned. Natural spawns are the
     * obvious case, but a spawn egg is a plain item use that no build check covers, so the
     * reasons a <i>player</i> can trigger are refused too. CUSTOM is deliberately left
     * alone — that is how Citizens puts the market's own NPCs on the ground.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!config.isMarketWorld(event.getLocation().getWorld())) return;
        if (!(event.getEntity() instanceof Monster)) return;
        switch (event.getSpawnReason()) {
            case NATURAL, SPAWNER, SPAWNER_EGG, DISPENSE_EGG, EGG, BUILD_WITHER, TRIAL_SPAWNER,
                 RAID, PATROL, VILLAGE_INVASION, REINFORCEMENTS, LIGHTNING -> event.setCancelled(true);
            default -> {
            }
        }
    }

    /**
     * Stepping on things counts as building: pressure plates, tripwire and farmland trample
     * all reach the world through a PHYSICAL interaction that no click handler sees.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysical(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.PHYSICAL) return;
        Block block = event.getClickedBlock();
        if (block == null || !config.isMarketWorld(block.getWorld())) return;
        if (!canBuild(event.getPlayer(), block.getLocation())) event.setCancelled(true);
    }

    /**
     * A stall's floor is not a free-for-all: goods dropped inside a plot belong to whoever
     * may build there, so a bystander cannot stand at the boundary and hoover them up.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Location where = event.getItem().getLocation();
        if (!config.isMarketWorld(where.getWorld())) return;
        if (plots.regionAt(where) == null) return; // open floor: finders keepers
        if (!canBuild(player, where)) event.setCancelled(true);
    }

    /**
     * Item frames and paintings are decoration on somebody's stall wall — breaking one is
     * building, and {@code BlockBreakEvent} never fires for them.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!config.isMarketWorld(event.getEntity().getWorld())) return;
        if (event.getRemover() instanceof Player player && canBuild(player, event.getEntity().getLocation())) return;
        event.setCancelled(true);
    }

    /**
     * The same frame can also pop off because its support vanished or an explosion reached
     * it — {@code HangingBreakByEntityEvent} never fires for those, and the item inside
     * would drop to the floor for anyone to take.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreakPhysics(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent) return; // handled above, with its build check
        if (!config.isMarketWorld(event.getEntity().getWorld())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (!config.isMarketWorld(event.getEntity().getWorld())) return;
        Player player = event.getPlayer();
        if (player == null || !canBuild(player, event.getEntity().getLocation())) event.setCancelled(true);
    }

    /**
     * Armour stands, item frames and chest minecarts are containers by another name — the
     * same rule as {@link #onContainerInteract} applies to their right-click.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (!config.isMarketWorld(target.getWorld())) return;
        if (!(target instanceof ArmorStand || target instanceof ItemFrame || target instanceof InventoryHolder)) return;
        if (!canBuild(event.getPlayer(), target.getLocation())) event.setCancelled(true);
    }

    /**
     * Endermen carrying blocks off, sheep eating grass, falling blocks landing on a stall.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        if (event.getEntity() instanceof Player player && canBuild(player, event.getBlock().getLocation())) return;
        event.setCancelled(true);
    }

    /**
     * Fire never spreads or burns here, whoever lit it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        Player igniter = event.getPlayer();
        if (igniter != null && canBuild(igniter, event.getBlock().getLocation())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (config.isMarketWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (config.isMarketWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    // -------------------------------------------------------------------------
    // Plot borders
    // -------------------------------------------------------------------------
    //
    // Build rights are checked against the player who swung, but three mechanics move
    // blocks with no player attached and would otherwise carry a renter's changes out of
    // their own cuboid — into the walkway, or into the neighbouring stall. The rule for
    // all three is the same: a change may not cross from one plot into anything else.

    /**
     * Water and lava stay in the stall they were poured in.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        if (crossesPlotBorder(event.getBlock().getLocation(), event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Nor does a piston get to push them out, or pull a neighbour's wall in.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        if (pistonLeavesPlot(event.getBlock(), event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!config.isMarketWorld(event.getBlock().getWorld())) return;
        if (pistonLeavesPlot(event.getBlock(), event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    /**
     * A dispenser is a piston with better manners — it can still put water, lava or fire
     * on the other side of a wall it doesn't own.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        if (!config.isMarketWorld(block.getWorld())) return;
        if (!(block.getBlockData() instanceof Directional directional)) return;
        Block target = block.getRelative(directional.getFacing());
        if (crossesPlotBorder(block.getLocation(), target.getLocation())) event.setCancelled(true);
    }

    /**
     * A hopper under a neighbour's wall is a chest shop's stock walking out of it. The
     * same border rule applies: items move within a plot, or not at all.
     *
     * <p>This fires on every hopper tick in the world, so the market-world check comes
     * before anything that allocates.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        Location source = event.getSource().getLocation();
        if (source == null || !config.isMarketWorld(source.getWorld())) return;
        Location destination = event.getDestination().getLocation();
        if (destination == null) return;
        if (crossesPlotBorder(source, destination)) event.setCancelled(true);
    }

    /**
     * Whether {@code to} lies in a different plot from {@code from} — which includes
     * leaving a plot for the open market floor, and entering one from it.
     */
    private boolean crossesPlotBorder(Location from, Location to) {
        PlotRegion source = plots.regionAt(from);
        PlotRegion destination = plots.regionAt(to);
        return source != destination;
    }

    /**
     * Every block a piston is about to move, plus the space each one lands in — a push is
     * only legal if the piston, its cargo, and every destination sit in the same plot.
     */
    private boolean pistonLeavesPlot(Block piston, List<Block> moved, BlockFace direction) {
        PlotRegion home = plots.regionAt(piston.getLocation());
        for (Block block : moved) {
            if (plots.regionAt(block.getLocation()) != home) return true;
            if (plots.regionAt(block.getRelative(direction).getLocation()) != home) return true;
        }
        return false;
    }
}
