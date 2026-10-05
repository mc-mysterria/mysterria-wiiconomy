package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.model.EntranceItem;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.EntranceService;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketBounds;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketEntrance;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketModule;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.model.source.MarketNpcRole;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.listener.PlotWandListener;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRegion;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRental;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotService;
import dev.ua.ikeepcalm.wiic.utils.AuditActor;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.MysterriaAuditBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code /wiicmarket} — admin plumbing for the Underground Market: NPC placement,
 * hub/exit entrance registration, entrance item give, GUI test access, reload.
 * Player-facing market interaction is NPC-only by design; this command is gated
 * by {@code wiic.market.admin} in plugin.yml.
 */
public class MarketAdminCommand implements CommandExecutor, TabCompleter {

    private final MarketModule module;

    public MarketAdminCommand(MarketModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        AuditActor actor = AuditActor.of(sender);
        // One row per invocation whatever the subcommand does; the rows below carry the detail.
        actor.observed(label, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            usage(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "npc" -> handleNpc(player, args, actor);
            case "entrance" -> handleEntrance(player, args, actor);
            case "plot" -> handlePlot(player, args, actor);
            case "bounds" -> handleBounds(player, args, actor);
            case "open" -> handleOpen(player, args);
            case "give-entrance" -> {
                ItemStack entranceItem = EntranceItem.create(module.getConfig());
                boolean given = ItemUtil.giveOrDrop(player, entranceItem);
                player.sendMessage(Component.text("Secret entrance item given.").color(NamedTextColor.GREEN));
                actor.emit("admin.entrance_item.given", given ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                        AuditRisk.HIGH, player.getUniqueId(), given ? null : "recipient no longer online",
                        AuditActor.fields("material", entranceItem.getType().name().toLowerCase(Locale.ROOT),
                                "item_amount", entranceItem.getAmount()));
            }
            case "appraise" -> handleAppraise(player);
            case "reload" -> {
                try {
                    module.getConfig().reload();
                    module.getServices().plots().reloadRegions();
                    module.getServices().prices().invalidate();
                } catch (RuntimeException failure) {
                    actor.emit("admin.market.reloaded", AuditOutcome.FAILED, AuditRisk.LOW, null,
                            failure.getClass().getSimpleName(), AuditActor.fields());
                    throw failure;
                }
                player.sendMessage(Component.text("market.yml reloaded.").color(NamedTextColor.GREEN));
                actor.emit("admin.market.reloaded", AuditOutcome.COMMITTED, AuditRisk.LOW, null, null,
                        AuditActor.fields());
            }
            default -> usage(player);
        }
        return true;
    }

    /**
     * Asks the Fence about the held item and prints the answer in full — the figure, the
     * per-unit figure, and which of his sources produced it.
     *
     * <p>Every complaint about the price guide is really a complaint about one of those
     * three, and there is otherwise no way to tell "the sequence index has never heard of
     * this ingredient" apart from "the sequence index says it is shallow": both come out
     * of the listing screen as one number.
     */
    private void handleAppraise(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(Component.text("Hold the item you want appraised.").color(NamedTextColor.RED));
            return;
        }
        module.getServices().prices().suggest(held, suggestion -> {
            var facts = suggestion.facts();
            player.sendMessage(Component.text(held.getType().name() + " x" + held.getAmount())
                    .color(NamedTextColor.GOLD));
            player.sendMessage(Component.text("  total " + suggestion.total()
                            + " coppets, unit " + suggestion.unitPrice())
                    .color(NamedTextColor.YELLOW));
            player.sendMessage(Component.text("  basis " + suggestion.basis()
                            + " (from " + suggestion.sampled() + " past sales)")
                    .color(NamedTextColor.GRAY));
            player.sendMessage(Component.text("  kind " + facts.kind()
                            + ", pathway " + facts.pathway()
                            + ", declared sequence " + facts.declaredSequence()
                            + ", served sequence " + facts.servedSequence()
                            + ", ingredient " + facts.ingredientKey())
                    .color(NamedTextColor.DARK_GRAY));
            player.sendMessage(Component.text("  value key " + facts.valueKey()).color(NamedTextColor.DARK_GRAY));
        });
    }

    private void handleNpc(Player player, String[] args, AuditActor actor) {
        if (module.getNpcService() == null) {
            player.sendMessage(Component.text("Citizens is not installed.").color(NamedTextColor.RED));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("create")) {
            MarketNpcRole role = args.length >= 3 ? MarketNpcRole.fromString(args[2]) : null;
            if (role == null) {
                player.sendMessage(Component.text("Usage: /wiicmarket npc create <role> [plotId]").color(NamedTextColor.RED));
                return;
            }
            String plotId = args.length >= 4 ? args[3] : null;
            if (role == MarketNpcRole.PLOT_VENDOR) {
                createPlotVendor(player, plotId, actor);
                return;
            }
            int npcId;
            try {
                npcId = module.getNpcService().create(role, plotId, player.getLocation()).getId();
            } catch (RuntimeException failure) {
                actor.emit("admin.npc.created", AuditOutcome.FAILED, AuditRisk.NORMAL, null,
                        failure.getClass().getSimpleName(),
                        AuditActor.fields("role", role.name(), "plot_id", plotId));
                throw failure;
            }
            player.sendMessage(Component.text("Market NPC (" + role + ") created.").color(NamedTextColor.GREEN));
            actor.emit("admin.npc.created", AuditOutcome.COMMITTED, AuditRisk.NORMAL, null, null,
                    AuditActor.fields("role", role.name(), "npc_id", npcId, "plot_id", plotId));
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
            var removed = module.getNpcService().removeNearestNpc(player.getLocation(), 5);
            player.sendMessage(removed != null
                    ? Component.text("Nearest market NPC removed.").color(NamedTextColor.GREEN)
                    : Component.text("No market NPC within 5 blocks.").color(NamedTextColor.RED));
            if (removed != null) {
                actor.emit("admin.npc.removed", AuditOutcome.COMMITTED, AuditRisk.NORMAL, null, null,
                        AuditActor.fields("npc_id", removed.id()));
            }
        } else {
            player.sendMessage(Component.text("Usage: /wiicmarket npc <create|remove>").color(NamedTextColor.RED));
        }
    }

    /**
     * Places a stall vendor by hand. Unlike every other role this one is normally spawned
     * by {@code PlotService} when a plot is rented, so a hand-placed one is a repair: the
     * only way back if Citizens loses its registry and an existing rental's vendor with it.
     *
     * <p>It is useless without a plot — a vendor with no plot id opens nothing at all — and
     * an unregistered one would outlive the tenancy it belongs to, so both are refused here
     * rather than left to be discovered as an NPC that silently does nothing.
     */
    private void createPlotVendor(Player player, @Nullable String plotId, AuditActor actor) {
        if (plotId == null) {
            player.sendMessage(Component.text("A stall vendor needs the plot it serves: "
                    + "/wiicmarket npc create plot_vendor <plotId>").color(NamedTextColor.RED));
            return;
        }
        PlotService plots = module.getServices().plots();
        if (plots.region(plotId) == null) {
            player.sendMessage(Component.text("No plot called '" + plotId
                    + "'. Define it first with /wiicmarket plot define.").color(NamedTextColor.RED));
            return;
        }
        MysterriaAuditBridge.AuditIdentity identity = MysterriaAuditBridge.randomIdentity("admin-plot-vendor");
        int npcId;
        try {
            npcId = module.getNpcService().spawnPlotVendor(plotId, player.getLocation());
        } catch (RuntimeException failure) {
            actor.emit("admin.npc.plot_vendor_created", AuditOutcome.FAILED, AuditRisk.NORMAL, null, identity,
                    failure.getClass().getSimpleName(), AuditActor.fields("plot_id", plotId));
            throw failure;
        }
        // The saved binding finishes later on the DB thread; it gets its own row.
        plots.bindVendor(plotId, npcId, persisted -> actor.emit("admin.npc.plot_vendor_bound",
                persisted ? AuditOutcome.COMMITTED : AuditOutcome.FAILED, AuditRisk.NORMAL, null, identity,
                persisted ? null : "vendor binding was not saved; it will not be removed on eviction",
                AuditActor.fields("plot_id", plotId, "npc_id", npcId)));
        PlotRental rental = plots.rental(plotId);
        actor.emit("admin.npc.plot_vendor_created", AuditOutcome.COMMITTED, AuditRisk.NORMAL,
                rental == null ? null : rental.renterUuid(), identity, null,
                AuditActor.fields("plot_id", plotId, "npc_id", npcId,
                        "renter_name", rental == null ? null : rental.renterName()));
        player.sendMessage(Component.text("Stall vendor for " + plotId + " placed"
                + (rental == null
                ? " — the plot is unrented, so it will show an empty storefront until someone takes it."
                : " and bound to " + rental.renterName() + ".")).color(NamedTextColor.GREEN));
    }

    private void handleEntrance(Player player, String[] args, AuditActor actor) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /wiicmarket entrance <hub-here|exit-here|list|remove>").color(NamedTextColor.RED));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "hub-here" -> {
                Block target = player.getTargetBlockExact(5);
                if (target == null || target.getType().isAir()) {
                    player.sendMessage(Component.text("Look at the block that should become the hub entrance.").color(NamedTextColor.RED));
                    return;
                }
                module.getServices().entrances().registerHub(player, target, success ->
                        player.sendMessage(success
                                ? Component.text("Hub entrance registered.").color(NamedTextColor.GREEN)
                                : Component.text("Failed to register hub entrance.").color(NamedTextColor.RED)));
            }
            case "exit-here" -> {
                Block target = player.getTargetBlockExact(5);
                if (target == null || target.getType().isAir()) {
                    player.sendMessage(Component.text("Look at the block that should become the exit door.").color(NamedTextColor.RED));
                    return;
                }
                if (!module.getConfig().isMarketWorld(target.getWorld())) {
                    player.sendMessage(Component.text("The exit door must be inside the market world.").color(NamedTextColor.RED));
                    return;
                }
                Block door = EntranceService.resolveLowerDoorBlock(target);
                List<Integer> previousExit = module.getConfig().raw().getIntegerList("entrance.exit-door");
                module.getConfig().raw().set("entrance.exit-door",
                        List.of(door.getX(), door.getY(), door.getZ()));
                module.getConfig().save();
                boolean saved = module.getConfig().lastSaveSucceeded();
                player.sendMessage(Component.text("Exit door set at " + door.getX() + " " + door.getY()
                        + " " + door.getZ() + ".").color(NamedTextColor.GREEN));
                actor.emit("admin.entrance.exit_door_set", saved ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                        AuditRisk.LOW, null, saved ? null : "market.yml save failed",
                        AuditActor.fields("old_exit_door", previousExit.size() < 3 ? "unset"
                                        : previousExit.get(0) + " " + previousExit.get(1) + " " + previousExit.get(2),
                                "new_exit_door", door.getX() + " " + door.getY() + " " + door.getZ()));
            }
            case "list" -> {
                List<MarketEntrance> all = module.getServices().entrances().all();
                player.sendMessage(Component.text("Entrances (" + all.size() + "):").color(NamedTextColor.GOLD));
                for (MarketEntrance e : all) {
                    player.sendMessage(Component.text(" - " + (e.isHub() ? "HUB" : "land " + e.landId())
                            + " @ " + e.world() + " " + e.x() + " " + e.y() + " " + e.z()).color(NamedTextColor.GRAY));
                }
            }
            case "remove" -> {
                Block target = player.getTargetBlockExact(5);
                MarketEntrance entrance = target != null
                        ? module.getServices().entrances().entranceAt(target)
                        : null;
                if (entrance == null) {
                    player.sendMessage(Component.text("Look at a registered entrance door.").color(NamedTextColor.RED));
                    return;
                }
                module.getServices().entrances().remove(entrance, "admin remove by " + player.getName(),
                        actor, trailing(args, 2));
                player.sendMessage(Component.text("Entrance removed.").color(NamedTextColor.GREEN));
            }
            default -> player.sendMessage(Component.text("Usage: /wiicmarket entrance <hub-here|exit-here|list|remove>").color(NamedTextColor.RED));
        }
    }

    /**
     * The plot-definition flow: {@code wand} → click two corners → {@code define <id>}
     * (writes market.yml and captures the pristine snapshot) → {@code vendorspot <id>}
     * → optionally {@code evict <id>} / {@code list}.
     */
    private void handlePlot(Player player, String[] args, AuditActor actor) {
        PlotService plots = module.getServices().plots();
        if (args.length < 2) {
            player.sendMessage(Component.text(
                    "Usage: /wiicmarket plot <wand|define|vendorspot|snapshot|list|evict>").color(NamedTextColor.RED));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "wand" -> {
                boolean given = ItemUtil.giveOrDrop(player, PlotWandListener.create());
                player.sendMessage(Component.text(
                        "Plot wand given. Left-click and right-click the two corners.").color(NamedTextColor.GREEN));
                actor.emit("admin.plot_wand.given", given ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                        AuditRisk.LOW, player.getUniqueId(), given ? null : "recipient no longer online",
                        AuditActor.fields());
            }
            case "define" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /wiicmarket plot define <id>").color(NamedTextColor.RED));
                    return;
                }
                if (!module.getConfig().isMarketWorld(player.getWorld())) {
                    player.sendMessage(Component.text("Plots must be defined inside the market world.").color(NamedTextColor.RED));
                    return;
                }
                PlotWandListener.Selection selection = module.getPlotWand().selection(player);
                if (selection == null || !selection.complete()) {
                    player.sendMessage(Component.text(
                            "Select both corners with the plot wand first.").color(NamedTextColor.RED));
                    return;
                }
                String plotId = args[2].toLowerCase(Locale.ROOT);
                if (rentedWarning(player, plots, plotId, "define")) return;
                PlotRegion clash = plots.overlapping(selection.first(), selection.second(), plotId);
                if (clash != null) {
                    player.sendMessage(Component.text("That selection overlaps plot '" + clash.id()
                            + "'. Plots may not share blocks.").color(NamedTextColor.RED));
                    return;
                }
                int volume = volumeOf(selection);
                int maxVolume = module.getConfig().plotMaxVolume();
                if (volume > maxVolume) {
                    player.sendMessage(Component.text("Selection is " + volume + " blocks; the cap is "
                            + maxVolume + " (plots.max-volume). Pick a smaller stall.").color(NamedTextColor.RED));
                    return;
                }
                PlotRegion previous = plots.region(plotId);
                module.getConfig().savePlotRegion(plotId, selection.first(), selection.second());
                boolean saved = module.getConfig().lastSaveSucceeded();
                plots.reloadRegions();
                module.getPlotWand().clear(player);
                PlotRegion region = plots.region(plotId);
                boolean parsed = region != null;
                actor.emit("admin.plot.defined", saved && parsed ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                        AuditRisk.NORMAL, null,
                        !saved ? "market.yml save failed" : parsed ? null : "saved but could not be parsed back",
                        AuditActor.fields("plot_id", plotId, "volume", volume,
                                "old_region", previous == null ? "none" : corners(previous),
                                "new_region", parsed ? corners(region) : "unparsed"));
                if (region == null) {
                    player.sendMessage(Component.text("Plot saved but could not be parsed back.").color(NamedTextColor.RED));
                    return;
                }
                player.sendMessage(Component.text("Plot '" + plotId + "' defined (" + region.volume()
                        + " blocks). Capturing snapshot...").color(NamedTextColor.GREEN));
                captureSnapshot(player, plots, region, actor, "define");
            }
            case "vendorspot" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /wiicmarket plot vendorspot <id>").color(NamedTextColor.RED));
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                PlotRegion existing = plots.region(id);
                if (existing == null) {
                    player.sendMessage(Component.text("No such plot: " + id).color(NamedTextColor.RED));
                    return;
                }
                var oldSpot = existing.vendorSpot();
                var here = player.getLocation();
                module.getConfig().savePlotVendorSpot(id, here);
                boolean saved = module.getConfig().lastSaveSucceeded();
                plots.reloadRegions();
                player.sendMessage(Component.text("Vendor spot for '" + id
                        + "' set where you stand.").color(NamedTextColor.GREEN));
                actor.emit("admin.plot.vendorspot_set", saved ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                        AuditRisk.LOW, null, saved ? null : "market.yml save failed",
                        AuditActor.fields("plot_id", id,
                                "old_spot", oldSpot == null ? "none"
                                        : spot(oldSpot.x(), oldSpot.y(), oldSpot.z(), oldSpot.yaw()),
                                "new_spot", spot(here.getX(), here.getY(), here.getZ(), here.getYaw())));
            }
            case "snapshot" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /wiicmarket plot snapshot <id>").color(NamedTextColor.RED));
                    return;
                }
                PlotRegion region = plots.region(args[2].toLowerCase(Locale.ROOT));
                if (region == null) {
                    player.sendMessage(Component.text("No such plot: " + args[2]).color(NamedTextColor.RED));
                    return;
                }
                if (rentedWarning(player, plots, region.id(), "snapshot")) return;
                captureSnapshot(player, plots, region, actor, "snapshot");
            }
            case "list" -> {
                var regions = plots.allRegions();
                player.sendMessage(Component.text("Plots (" + regions.size() + "):").color(NamedTextColor.GOLD));
                for (PlotRegion region : regions) {
                    PlotRental rental = plots.rental(region.id());
                    String status = rental == null
                            ? "free"
                            : rental.renterName() + " until " + rental.paidUntil();
                    player.sendMessage(Component.text(" - " + region.id() + " [" + region.volume() + " blocks] "
                            + status + (region.vendorSpot() == null ? " (no vendor spot)" : ""))
                            .color(NamedTextColor.GRAY));
                }
            }
            case "evict" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /wiicmarket plot evict <id>").color(NamedTextColor.RED));
                    return;
                }
                PlotRental rental = plots.rental(args[2].toLowerCase(Locale.ROOT));
                if (rental == null) {
                    player.sendMessage(Component.text("That plot is not rented.").color(NamedTextColor.RED));
                    return;
                }
                plots.evict(rental, "admin evict by " + player.getName(), actor, trailing(args, 3), success ->
                        player.sendMessage(success
                                ? Component.text("Plot evicted; contents moved to " + rental.renterName()
                                + "'s stash.").color(NamedTextColor.GREEN)
                                : Component.text("Eviction failed — see console.").color(NamedTextColor.RED)));
            }
            default -> player.sendMessage(Component.text(
                    "Usage: /wiicmarket plot <wand|define|vendorspot|snapshot|list|evict>").color(NamedTextColor.RED));
        }
    }

    /**
     * The containment envelope, drawn with the same wand as a plot. Deliberately reuses
     * the plot wand rather than adding a second tool — an admin defining the market's outer
     * box is doing the same thing as defining a stall, at a different scale.
     */
    private void handleBounds(Player player, String[] args, AuditActor actor) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "show";
        switch (action) {
            case "set" -> {
                if (!module.getConfig().isMarketWorld(player.getWorld())) {
                    player.sendMessage(Component.text(
                            "The containment envelope must be drawn inside the market world.").color(NamedTextColor.RED));
                    return;
                }
                PlotWandListener.Selection selection = module.getPlotWand().selection(player);
                if (selection == null || !selection.complete()) {
                    player.sendMessage(Component.text(
                            "Select both corners with the plot wand first (/wiicmarket plot wand).").color(NamedTextColor.RED));
                    return;
                }
                MarketBounds previous = module.getConfig().bounds();
                module.getConfig().saveBounds(selection.first(), selection.second());
                boolean saved = module.getConfig().lastSaveSucceeded();
                module.getPlotWand().clear(player);
                MarketBounds bounds = module.getConfig().bounds();
                actor.emit("admin.bounds.set", saved && bounds != null ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                        AuditRisk.NORMAL, null,
                        !saved ? "market.yml save failed" : bounds != null ? null : "saved but could not be parsed back",
                        AuditActor.fields("old_bounds", previous == null ? "none" : previous.describe(),
                                "new_bounds", bounds == null ? "unparsed" : bounds.describe()));
                if (bounds == null) {
                    player.sendMessage(Component.text("Bounds saved but could not be parsed back.").color(NamedTextColor.RED));
                    return;
                }
                player.sendMessage(Component.text("Containment envelope set: " + bounds.describe())
                        .color(NamedTextColor.GREEN));
                warnIfArrivalOutside(player, bounds);
            }
            case "clear" -> {
                MarketBounds previous = module.getConfig().bounds();
                module.getConfig().clearBounds();
                boolean saved = module.getConfig().lastSaveSucceeded();
                player.sendMessage(Component.text("Containment envelope cleared — only the void floor is "
                        + "enforced now.").color(NamedTextColor.YELLOW));
                actor.emit("admin.bounds.cleared", saved ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                        AuditRisk.NORMAL, null, saved ? null : "market.yml save failed",
                        AuditActor.fields("old_bounds", previous == null ? "none" : previous.describe()));
            }
            default -> {
                MarketBounds bounds = module.getConfig().bounds();
                if (bounds == null) {
                    player.sendMessage(Component.text("No containment envelope defined. Draw one with the plot "
                            + "wand, then /wiicmarket bounds set.").color(NamedTextColor.YELLOW));
                    return;
                }
                player.sendMessage(Component.text("Containment envelope: " + bounds.describe())
                        .color(NamedTextColor.GOLD));
                player.sendMessage(Component.text("You are currently "
                                + (bounds.contains(player.getLocation()) ? "inside" : "OUTSIDE") + " it.")
                        .color(NamedTextColor.GRAY));
                warnIfArrivalOutside(player, bounds);
            }
        }
    }

    /**
     * An arrival point outside the envelope would have containment bouncing every visitor
     * straight back out again, so it is worth saying loudly at the moment it is set.
     */
    private void warnIfArrivalOutside(Player player, MarketBounds bounds) {
        var arrival = module.getConfig().arrival();
        if (arrival != null && !bounds.contains(arrival)) {
            player.sendMessage(Component.text("WARNING: the arrival point is outside this envelope. "
                    + "Fix 'arrival' in market.yml or redraw the bounds.").color(NamedTextColor.RED));
        }
    }

    /**
     * Refuses to snapshot a stall somebody is living in.
     *
     * <p>The snapshot is the state an eviction restores, and both {@code define} and
     * {@code snapshot} capture the cuboid <em>as it stands right now</em>. Run either on a
     * rented plot and the renter's own build becomes the pristine baseline — the stall can
     * then never be cleared, and the next renter inherits it. Silent, permanent, and
     * invisible until an eviction restores the wrong thing months later.
     *
     * @return true when the command should stop.
     */
    private static boolean rentedWarning(Player player, PlotService plots, String plotId, String action) {
        PlotRental rental = plots.rental(plotId);
        if (rental == null) return false;
        player.sendMessage(Component.text("Plot '" + plotId + "' is rented by " + rental.renterName()
                + ". Running " + action + " now would save their build as the pristine state.")
                .color(NamedTextColor.RED));
        player.sendMessage(Component.text("Evict or wait for the rental to lapse first: "
                + "/wiicmarket plot evict " + plotId).color(NamedTextColor.GRAY));
        return true;
    }

    private static int volumeOf(PlotWandListener.Selection selection) {
        int[] a = selection.first();
        int[] b = selection.second();
        return (Math.abs(a[0] - b[0]) + 1) * (Math.abs(a[1] - b[1]) + 1) * (Math.abs(a[2] - b[2]) + 1);
    }

    private void captureSnapshot(Player player, PlotService plots, PlotRegion region, AuditActor actor,
                                 String trigger) {
        plots.captureSnapshot(region, success -> {
            player.sendMessage(success
                    ? Component.text("Snapshot stored for '" + region.id()
                    + "' — evictions will restore this state.").color(NamedTextColor.GREEN)
                    : Component.text("Snapshot failed for '" + region.id()
                    + "' — see console.").color(NamedTextColor.RED));
            // The stored blob it replaces is not read back, so the row cannot carry the old one.
            actor.emit("admin.plot.snapshot", success ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                    AuditRisk.NORMAL, null, success ? null : "snapshot capture or store failed",
                    AuditActor.fields("plot_id", region.id(), "trigger", trigger, "volume", region.volume()));
        });
    }

    private static String corners(PlotRegion region) {
        return region.minX() + " " + region.minY() + " " + region.minZ() + " -> "
                + region.maxX() + " " + region.maxY() + " " + region.maxZ();
    }

    private static String spot(double x, double y, double z, float yaw) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f yaw %.0f", x, y, z, yaw);
    }

    /** The optional free-text reason after the fixed arguments, or null when none was given. */
    private static @Nullable String trailing(String[] args, int from) {
        if (args.length <= from) return null;
        String text = String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim();
        if (text.isEmpty()) return null;
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private void handleOpen(Player player, String[] args) {
        String gui = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "browse";
        MarketNpcRole role = switch (gui) {
            case "broker" -> MarketNpcRole.BROKER;
            case "banker", "stash", "ledger" -> MarketNpcRole.BANKER;
            case "informant" -> MarketNpcRole.INFORMANT;
            case "shop" -> MarketNpcRole.SHOPKEEPER;
            case "plots", "warden" -> MarketNpcRole.PLOT_WARDEN;
            case "courier" -> MarketNpcRole.COURIER_POST;
            default -> MarketNpcRole.CLERK;
        };
        module.openGui(player, role, null);
    }

    private void usage(Player player) {
        player.sendMessage(Component.text(
                "/wiicmarket <npc|entrance|plot|bounds|open|give-entrance|appraise|reload>").color(NamedTextColor.GOLD));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(args[0], Stream.of("npc", "entrance", "plot", "bounds", "open", "give-entrance", "appraise", "reload"));
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "npc" -> filter(args[1], Stream.of("create", "remove"));
                case "entrance" -> filter(args[1], Stream.of("hub-here", "exit-here", "list", "remove"));
                case "plot" -> filter(args[1], Stream.of("wand", "define", "vendorspot", "snapshot", "list", "evict"));
                case "bounds" -> filter(args[1], Stream.of("show", "set", "clear"));
                case "open" -> filter(args[1],
                        Stream.of("browse", "broker", "banker", "informant", "shop", "plots", "courier"));
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("create")) {
            return filter(args[2], Stream.of(MarketNpcRole.values()).map(r -> r.name().toLowerCase(Locale.ROOT)));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("plot")
                && Stream.of("vendorspot", "snapshot", "evict").anyMatch(args[1]::equalsIgnoreCase)) {
            return filter(args[2], module.getServices().plots().allRegions().stream().map(PlotRegion::id));
        }
        // The plot a stall vendor serves — mandatory, so offer it rather than let an admin
        // discover the requirement by being refused.
        if (args.length == 4 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("create")
                && MarketNpcRole.fromString(args[2]) == MarketNpcRole.PLOT_VENDOR) {
            return filter(args[3], module.getServices().plots().allRegions().stream().map(PlotRegion::id));
        }
        return List.of();
    }

    private static List<String> filter(String prefix, Stream<String> options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.startsWith(lower)).toList();
    }
}
