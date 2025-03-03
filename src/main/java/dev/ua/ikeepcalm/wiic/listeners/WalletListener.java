package dev.ua.ikeepcalm.wiic.listeners;

import dev.ua.ikeepcalm.market.util.ItemStackUtil;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.economy.Appraiser;
import dev.ua.ikeepcalm.wiic.economy.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.guis.WalletGUI;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


public class WalletListener implements Listener {

    private final Appraiser appraiser;
    private final WalletManager walletManager;
    private final SoldItemsManager soldItemsManager;
    private final Map<Player, ItemStack> offhandItems = new HashMap<>();

    public WalletListener() {
        this.appraiser = new Appraiser();
        this.walletManager = new WalletManager();
        this.soldItemsManager = new SoldItemsManager(WIIC.INSTANCE);
    }

    @EventHandler
    public void onWalletInventoryClick(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        ItemStack item = event.getItem();
        if (event.getAction().equals(Action.LEFT_CLICK_AIR) || event.getAction().equals(Action.LEFT_CLICK_BLOCK))
            return;
        if (WalletUtil.isWallet(item)) {
            if (WalletUtil.wasBound(item)) {
                if (p.getInventory().getItemInOffHand().getType() != Material.AIR) {
                    offhandItems.put(p, p.getInventory().getItemInOffHand());
                    p.getInventory().setItemInOffHand(null);
                }
                Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> openVaultInventory(p));
            }
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
    private void openVaultInventory(Player p) {
        if (WIIC.getEcon().hasAccount(p.getUniqueId())) {
            BigDecimal balance = WIIC.getEcon().balance("iConomyUnlocked", p.getUniqueId());
            WalletData data = new WalletData(balance.intValue());
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1, 1);
            Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> new WalletGUI(
                    appraiser,
                    walletManager,
                    soldItemsManager
            ).open(p, data, () -> returnOffhandItem(p)));
        } else {
            p.sendMessage(Component.text("Не ініціалізовано. Потримай гаманець у руках декілька секунд, і спробуй ще раз!").color(NamedTextColor.RED));
        }
    }

    private void returnOffhandItem(Player player) {
        if (offhandItems.containsKey(player)) {
            if (player.getInventory().getItemInOffHand().getType().equals(Material.AIR)) {
                player.getInventory().setItemInOffHand(offhandItems.remove(player));
            } else {
                ItemStackUtil.giveOrDrop(player, offhandItems.remove(player));
            }
        }
    }


}

