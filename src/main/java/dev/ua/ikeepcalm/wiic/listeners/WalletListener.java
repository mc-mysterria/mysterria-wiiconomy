package dev.ua.ikeepcalm.wiic.listeners;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.economy.Appraiser;
import dev.ua.ikeepcalm.wiic.economy.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.guis.WalletGUI;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.UUID;


public class WalletListener implements Listener {

    private final Appraiser appraiser;
    private final WalletManager walletManager;
    private final SoldItemsManager soldItemsManager;

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
            if (p.getInventory().getItemInOffHand().getType() != Material.AIR) {
                p.sendMessage(Component.text("Відкладіть предмети з лівої руки").color(NamedTextColor.RED));
                return;
            }
            openVaultInventory(p, WalletUtil.getWalletId(item));
        }
    }

    @EventHandler
    private void playerCraftEvent(CraftItemEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (WalletUtil.isWallet(result)) {
            UUID id = UUID.randomUUID();
            WalletUtil.setWalletData(result, id, event.getWhoClicked().getName());
            walletManager.createWallet(id, event.getWhoClicked().getName());
        }
        event.getInventory().setResult(result);
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

    @EventHandler
    public void onHandSwap(PlayerSwapHandItemsEvent event) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = event.getPlayer().getInventory().getItem(i);
            if (WalletUtil.isWallet(item)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // Function to open the vault inventory
    private void openVaultInventory(Player p, String id) {
        WalletData data = walletManager.getWallet(id);
        if (data == null) {
            WIIC.INSTANCE.getLogger().info(p.getName() + " tried to open a wallet that doesn't exist");
            WIIC.INSTANCE.getLogger().info("Wallet ID: " + id);
            p.sendMessage(Component.text("Виникла помилка! Зверніться до адміністратора.").color(NamedTextColor.RED));
            return;
        }
        new WalletGUI(appraiser, walletManager, soldItemsManager).openVault(p, data);
    }


}

