package dev.ua.ikeepcalm.wiic.listeners;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.wallet.models.WalletData;
import dev.ua.ikeepcalm.wiic.domain.wallet.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.domain.wallet.services.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.gui.WalletGUI;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import xyz.xenondevs.invui.window.WindowManager;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


public class WalletListener implements Listener {

    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;
    private final Map<Player, ItemStack> offhandItems = new HashMap<>();

    public WalletListener() {
        this.priceAppraiser = new PriceAppraiser();
        this.soldItemsManager = new SoldItemsManager(WIIC.INSTANCE);
    }

    @EventHandler
    public void onWalletInventoryClick(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        ItemStack item = event.getItem();

        if (WalletGUI.playersWithOpenWallets.contains(p) || WalletUtil.isWallet(p.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction().equals(Action.LEFT_CLICK_AIR) || event.getAction().equals(Action.LEFT_CLICK_BLOCK))
            return;
        if (WalletUtil.isWallet(item)) {
            startOpeningVault(p);
        }
    }

    public void startOpeningVault(Player p) {
        if (WalletGUI.playersWithOpenWallets.contains(p)) return;
        WalletGUI.playersWithOpenWallets.add(p);
        if (p.getInventory().getItemInOffHand().getType() != Material.AIR) {
            offhandItems.put(p, p.getInventory().getItemInOffHand());
            p.getInventory().setItemInOffHand(null);
        }
        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> openVaultInventory(p));
    }

    @EventHandler
    public void onItemsSwap(PlayerSwapHandItemsEvent event) {
        if (WalletGUI.playersWithOpenWallets.contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // While any WIIC InvUI window is open, the player must not be able to
        // manipulate their own inventory. Every WIIC GUI is a split window whose
        // bottom half is the player's real inventory; the vault deposit/withdraw/
        // sell flow snapshots inventory contents when the GUI is built and only
        // reconciles them on confirm. Splitting a coin stack onto the cursor,
        // hotbar-swapping, or pushing coins into a bundle in the meantime would
        // desync that snapshot and let money be voided or duplicated.
        //
        // All interactive GUI slots live in the top inventory and are handled by
        // InvUI directly (Item slots never fire Bukkit click events), so every
        // click event that reaches us here is a player-inventory interaction with
        // no legitimate purpose. InvUI honours the cancellation and aborts the
        // underlying item move — including bundle inserts.
        if (WindowManager.getInstance().getOpenWindow(player) != null) {
            Inventory clicked = event.getClickedInventory();
            boolean ownInventory = clicked != null && clicked.equals(player.getInventory());
            boolean crossInventory = switch (event.getAction()) {
                case MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR, HOTBAR_SWAP -> true;
                default -> false;
            };
            if (ownInventory || crossInventory) {
                event.setCancelled(true);
                return;
            }
        }

        if (!WalletGUI.playersWithOpenWallets.contains(player)) return;
        // Belt-and-suspenders: block offhand swaps while the wallet session is
        // active but the player is looking at their own crafting view (no chest GUI).
        if (event.getInventory().getType() == InventoryType.CRAFTING && (event.getSlot() == 40 || event.getAction() == InventoryAction.HOTBAR_SWAP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // No WIIC GUI uses drag interactions, so any drag while a window is open
        // can only scatter items across the player's own inventory. Block it to
        // keep the vault snapshot in sync with reality.
        if (WindowManager.getInstance().getOpenWindow(player) != null) {
            event.setCancelled(true);
        }
    }

    private boolean containsSpecialItem(ItemStack[] items) {
        for (ItemStack item : items) {
            if (item != null && item.hasItemMeta() && ItemUtil.getType(item) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isNotSpecialRecipe(Recipe recipe) {
        return !(recipe instanceof Keyed keyed) || !keyed.getKey().getNamespace().equals(WIIC.getNamespace());
    }

    @EventHandler
    private void prepareCraftEvent(PrepareItemCraftEvent event) {
        if (containsSpecialItem(event.getInventory().getMatrix()) && isNotSpecialRecipe(event.getRecipe())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    private void onCrafterCraft(CrafterCraftEvent event) {
        if (containsSpecialItem(((Crafter) event.getBlock().getState()).getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    private void playerCraftEvent(CraftItemEvent event) {
        if (containsSpecialItem(event.getInventory().getMatrix()) && isNotSpecialRecipe(event.getRecipe())) {
            event.setCancelled(true);
            return;
        }

        ItemStack result = event.getInventory().getResult();
        event.getInventory().setResult(result);
        Bukkit.getScheduler().runTaskLater(WIIC.INSTANCE, () -> event.getWhoClicked().getInventory().setContents(
                Arrays.stream(event.getWhoClicked().getInventory().getContents())
                        .peek(item -> createWallet(item, event.getWhoClicked().getName()))
                        .toArray(ItemStack[]::new)
        ), 1);
    }

    private void createWallet(ItemStack wallet, String playerName) {
        if (WalletUtil.isWallet(wallet) && WalletUtil.getWalletId(wallet) == null) {
            UUID id = UUID.randomUUID();
            WalletUtil.setWalletData(wallet, id, playerName);
        }
    }

    @EventHandler
    public void onWalletPlaceEvent(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (WalletUtil.isWallet(item)) {
            if (event.getAction().equals(Action.LEFT_CLICK_AIR) || event.getAction().equals(Action.LEFT_CLICK_BLOCK)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!event.getKeepInventory()) {
            if (offhandItems.containsKey(event.getPlayer())) {
                event.getDrops().add(offhandItems.remove(event.getPlayer()));
            }
        } else returnOffhandItem(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        WalletGUI.playersWithOpenWallets.remove(event.getPlayer());
        returnOffhandItem(event.getPlayer());
    }

    // Function to open the vault inventory
    private void openVaultInventory(Player player) {
        if (WIIC.getEcon() != null) {
            BigDecimal balance = WIIC.getEcon().balance("iConomyUnlocked", player.getUniqueId());
            WalletData data = new WalletData(balance.intValue());
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1, 1);
            Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> new WalletGUI(
                    priceAppraiser,
                    soldItemsManager
            ).open(player, data, () -> {
                returnOffhandItem(player);
                WalletGUI.playersWithOpenWallets.remove(player);
            }));
        } else {
            player.sendMessage("Not initialized!");
            returnOffhandItem(player);
            WalletGUI.playersWithOpenWallets.remove(player);
        }
    }

    private void returnOffhandItem(Player player) {
        if (offhandItems.containsKey(player)) {
            if (player.getInventory().getItemInOffHand().getType().equals(Material.AIR)) {
                player.getInventory().setItemInOffHand(offhandItems.remove(player));
            } else {
                ItemUtil.giveOrDrop(player, offhandItems.remove(player));
            }
        }
    }

}

