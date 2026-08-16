package dev.ua.ikeepcalm.wiic.domain.agora.entrance.listener;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketFeedback;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.model.EntranceItem;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.EntranceService;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketEntrance;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes block interactions to {@link EntranceService}: placing a secret entrance
 * with the crafted item, entering through a registered door, leaving through the
 * market's exit door, and (de)registering on door break.
 */
public class EntranceListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final MarketConfig config;
    private final EntranceService entrances;
    private final MarketFeedback feedback;

    public EntranceListener(MarketConfig config, EntranceService entrances, MarketFeedback feedback) {
        this.config = config;
        this.entrances = entrances;
        this.feedback = feedback;
    }

    /**
     * Runs first and looks at cancelled events on purpose. A secret entrance normally
     * stands inside a claim, and a protection plugin refusing the right-click before WIIC
     * ever saw it left people unable to use their own door — and, worse, unable to use the
     * market's exit door if anything at all claimed the ground it stands on. Who may walk
     * through a door is this plugin's question to answer, not the claim's.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Player player = event.getPlayer();

        // Placing a new secret entrance with the crafted item.
        if (EntranceItem.isEntranceItem(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            // Remember the slot, not the stack. place() goes through the DB thread, and by
            // the time it answers the player may be holding something else — re-reading the
            // main hand then found no entrance item and consumed nothing, handing back a
            // free entrance item for every door built.
            int paidSlot = player.getInventory().getHeldItemSlot();
            entrances.place(player, block, result -> {
                switch (result) {
                    case SUCCESS -> {
                        if (!consumeEntranceItem(player, paidSlot)) {
                            WIIC.INSTANCE.getLogger().warning("Entrance placed by " + player.getName()
                                    + " but no entrance item remained to consume");
                        }
                        feedback.entranceForged(player, block.getRelative(BlockFace.UP).getLocation());
                        player.sendMessage(MM.deserialize(config.message("entrance-placed", "<dark_purple>The wall shivers... a hidden door now serves this land.")));
                    }
                    case NO_LANDS ->
                            send(player, "entrance-no-lands", "<red>Secret entrances require the Lands plugin.");
                    case NOT_IN_LAND ->
                            send(player, "entrance-not-in-land", "<red>The door must be hidden inside a claimed land.");
                    case NOT_TRUSTED -> send(player, "entrance-not-trusted", "<red>You are not trusted in this land.");
                    case LAND_HAS_ENTRANCE ->
                            send(player, "entrance-already-exists", "<red>This land already hides an entrance.");
                    case MARKET_WORLD -> send(player, "entrance-in-market", "<red>You cannot place an entrance here.");
                    case BAD_SPOT ->
                            send(player, "entrance-bad-spot", "<red>The door needs two blocks of open space above this spot.");
                    case ERROR -> send(player, "entrance-error", "<red>The ritual failed. Try again later.");
                }
            });
            return;
        }

        // Exit door inside the market world (either door half resolves to the stored position).
        Location exit = config.exitDoor();
        Block exitCandidate = EntranceService.resolveLowerDoorBlock(block);
        if (exit != null && config.isMarketWorld(block.getWorld()) && exitCandidate.getX() == exit.getBlockX() && exitCandidate.getY() == exit.getBlockY() && exitCandidate.getZ() == exit.getBlockZ()) {
            event.setCancelled(true);
            entrances.exit(player);
            return;
        }

        // Entering through a registered secret door.
        MarketEntrance entrance = entrances.entranceAt(block);
        if (entrance != null) {
            event.setCancelled(true);
            entrances.enter(player, entrance);
        }
    }

    /**
     * Takes exactly one entrance item off the player for a door that was just built. Tries
     * the slot they paid from first, then anywhere else in the inventory — the door exists
     * either way, so the one outcome that must not happen is keeping the item.
     */
    private boolean consumeEntranceItem(Player player, int paidSlot) {
        if (takeFromSlot(player, paidSlot)) return true;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (takeFromSlot(player, slot)) return true;
        }
        return false;
    }

    private boolean takeFromSlot(Player player, int slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (!EntranceItem.isEntranceItem(stack)) return false;
        int left = stack.getAmount() - 1;
        player.getInventory().setItem(slot, left > 0 ? stack.asQuantity(left) : null);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        // The exit door is the only way out of the market and is not part of the entrance
        // registry, so nothing else protects it. Breaking it would strand every player
        // inside until an admin re-registered one.
        if (isExitDoor(event.getBlock()) && !event.getPlayer().hasPermission("wiic.market.admin")) {
            event.setCancelled(true);
            send(event.getPlayer(), "exit-protected", "<red>The way out will not be moved.");
            return;
        }

        // Either door half, or the block holding the door up — knocking out the support
        // pops the door without a second BlockBreakEvent, which would leave a ghost row.
        MarketEntrance entrance = entrances.entranceAt(event.getBlock());
        if (entrance == null) entrance = entrances.entranceAt(event.getBlock().getRelative(BlockFace.UP));
        if (entrance == null) return;
        Player breaker = event.getPlayer();
        if (!entrances.canBreak(breaker, entrance)) {
            event.setCancelled(true);
            send(breaker, "entrance-protected", "<red>The door resists your attempts.");
            return;
        }
        entrances.remove(entrance, "broken by " + breaker.getName());
        // Handing the item back is what makes a door movable at all: without it, relocating
        // one costs a fresh set of echo shards, so nobody ever does.
        if (config.entranceRefundOnBreak()) {
            giveOrDrop(breaker, EntranceItem.create(config));
            send(breaker, "entrance-recovered",
                    "<gray>You work the door free of the wall. The passage closes behind it.");
        } else {
            send(breaker, "entrance-broken", "<gray>The hidden passage collapses. The land may craft a new one.");
        }
    }

    /** Into the breaker's hands, or at their feet when there is no room for it. */
    private static void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    /**
     * Explosions either spare registered doors or take them down with the registry
     * updated — never quietly leave a row pointing at rubble.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    /** True for either half of the market's registered exit door. */
    private boolean isExitDoor(Block block) {
        Location exit = config.exitDoor();
        if (exit == null || !config.isMarketWorld(block.getWorld())) return false;
        Block lower = EntranceService.resolveLowerDoorBlock(block);
        return lower.getX() == exit.getBlockX() && lower.getY() == exit.getBlockY() && lower.getZ() == exit.getBlockZ();
    }

    private void handleExplosion(List<Block> blocks) {
        List<Block> protectedBlocks = new ArrayList<>();
        for (Block block : blocks) {
            if (isExitDoor(block)) {
                protectedBlocks.add(block);
                continue;
            }
            MarketEntrance entrance = entrances.entranceAt(block);
            if (entrance == null) continue;
            if (config.entranceAllowBreak()) {
                entrances.remove(entrance, "destroyed by an explosion");
            } else {
                protectedBlocks.add(block);
            }
        }
        blocks.removeAll(protectedBlocks);
    }

    /**
     * Pistons never get to shove a registered door around — the registry can't follow it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesEntrance(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesEntrance(event.getBlocks())) event.setCancelled(true);
    }

    private boolean movesEntrance(List<Block> blocks) {
        for (Block block : blocks) {
            if (isExitDoor(block)) return true;
            if (entrances.entranceAt(block) != null) return true;
        }
        return false;
    }

    /**
     * Water and lava don't wash a door away either.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (isExitDoor(event.getToBlock())) {
            event.setCancelled(true);
            return;
        }
        if (entrances.isEmpty()) return;
        if (entrances.entranceAt(event.getToBlock()) != null) event.setCancelled(true);
    }

    private void send(Player player, String key, String def) {
        player.sendMessage(MM.deserialize(config.message(key, def)));
    }
}
