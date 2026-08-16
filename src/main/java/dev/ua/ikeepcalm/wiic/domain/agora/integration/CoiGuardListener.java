package dev.ua.ikeepcalm.wiic.domain.agora.integration;

import dev.ua.ikeepcalm.coi.api.event.*;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.ContainmentService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

/**
 * Shuts Beyonder powers off inside the market.
 *
 * <p>{@link dev.ua.ikeepcalm.wiic.domain.agora.market.listener.MarketProtectionListener}
 * already refuses ordinary block changes and damage, but an ability is a second route
 * to the same ends: CoI moves blocks, deals damage and summons creatures through its
 * own pipeline, and what that pipeline does never has to pass a
 * {@code BlockBreakEvent} to get there. A market where the walls are Bukkit-protected
 * but a Sequence 5 can still pull a stall apart is not protected.
 *
 * <p>This is the single class importing {@code dev.ua.ikeepcalm.coi.api} (compileOnly),
 * so it is also the only class that can fail to load when CircleOfImagination is
 * absent — the same soft-dependency boundary {@link CourierHook}, {@link LandsHook} and
 * {@link WorldsHook} draw. {@link #createIfAvailable} is the gate: it checks the plugin
 * is enabled before touching those types and treats a {@link LinkageError} — an
 * installed CoI build older than the API WIIC compiled against — as "unavailable". The
 * market then runs exactly as it does on a server with no CoI at all.
 *
 * <p>Note that {@code ItemInspector} reads CoI's item tags without this API at all,
 * straight off the PDC; listing and pricing beyonder goods therefore keep working
 * whether or not this guard could be installed.
 */
public class CoiGuardListener implements Listener {

    private final MarketConfig config;
    private final ContainmentService containment;

    private CoiGuardListener(MarketConfig config, ContainmentService containment) {
        this.config = config;
        this.containment = containment;
    }

    /**
     * The guard, or null when CoI is absent or exposes an API WIIC can't drive.
     */
    public static @Nullable CoiGuardListener createIfAvailable(WIIC plugin, MarketConfig config, ContainmentService containment) {
        if (!Bukkit.getPluginManager().isPluginEnabled("CircleOfImagination")) {
            plugin.getLogger().info("CircleOfImagination not found — market ability guards are inactive"
                    + " (nothing else in the market depends on it)");
            return null;
        }
        try {
            // First touch of the API types: probes classloading and signature match.
            MagicBlockEvent.getHandlerList();
            AbilityUsageEvent.getHandlerList();
            return new CoiGuardListener(config, containment);
        } catch (LinkageError e) {
            plugin.getLogger().severe("CircleOfImagination is installed but its API is incompatible with WIIC: "
                    + e.getMessage() + " — MARKET ABILITY GUARDS ARE INACTIVE, so abilities will work"
                    + " inside the market until this is resolved");
            return null;
        }
    }

    /**
     * No ability is cast in the market, whatever it does. Blocking at the cast is the
     * only check that catches an ability whose effect never surfaces as a block, a
     * damage tick or a spawn — teleports, item pulls, invisibility, anything.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAbilityUsage(AbilityUsageEvent event) {
        Player caster = event.getPlayer();
        if (caster == null || notGuarded(caster)) return;
        containment.refuse(event, caster, "containment-no-ability",
                "<dark_gray><i>You reach for it, and nothing answers. Down here that part of you is quiet.</i>");
    }

    /**
     * Blocks changed by an ability. Judged by where the block is, not who cast it —
     * this has to hold for an ability reaching in from outside, and for anything that
     * got past the cast check above.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMagicBlock(MagicBlockEvent event) {
        if (!config.blockAbilities()) return;
        Location location = event.getLocation();
        if (location == null || !config.isMarketWorld(location.getWorld())) return;
        // The event names its caster rather than carrying the player, so only a caster
        // who is online right now can be recognised as an admin and let through.
        String name = event.getPlayer();
        Player caster = name == null || name.isBlank() ? null : Bukkit.getPlayerExact(name);
        if (caster != null && containment.bypasses(caster)) return;
        event.setCancelled(true);
    }

    /**
     * Ability damage. The market cancels ordinary damage events already, but a magic hit
     * is routed through CoI's own pipeline — a separate path to the same harm, and one
     * that would otherwise make the market's one guarantee (nobody is hurt here) false.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMagicDamage(MagicDamageEvent event) {
        if (!config.blockAbilities()) return;
        Entity damaged = event.getDamaged();
        Player damager = event.getDamager();
        boolean inside = (damaged != null && config.isMarketWorld(damaged.getWorld()))
                || (damager != null && config.isMarketWorld(damager.getWorld()));
        if (!inside) return;
        if (damager != null && containment.bypasses(damager)) return;
        event.setCancelled(true);
    }

    /**
     * Summoned creatures. No caster is on the event, so there is no bypass — which
     * matches the market's blanket refusal of hostile spawns.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(BeyonderCreatureSpawnEvent event) {
        if (!config.blockAbilities()) return;
        Location location = event.getLocation();
        if (location == null || !config.isMarketWorld(location.getWorld())) return;
        event.setCancelled(true);
    }

    /**
     * Entering a mythical form, never leaving one — cancelling a DEACTIVATE would strand
     * a player in a form they were trying to drop, and the market is the last place that
     * should be able to trap someone in anything.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMythicalForm(MythicalFormEvent event) {
        if (event.getState() != MythicalFormEvent.MythicalFormState.ACTIVATE) return;
        Player player = event.getPlayer();
        if (player == null || notGuarded(player)) return;
        containment.refuse(event, player, "containment-no-ability",
                "<dark_gray><i>You reach for it, and nothing answers. Down here that part of you is quiet.</i>");
    }

    private boolean notGuarded(Player player) {
        return !config.blockAbilities()
                || !config.isMarketWorld(player.getWorld())
                || containment.bypasses(player);
    }
}
