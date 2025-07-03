package dev.ua.ikeepcalm.wiic.economy.vault;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.utils.WalletUtil;
import dev.ua.ikeepcalm.wiic.currency.services.WalletManager;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class VaultAdapter extends BukkitRunnable {

    private final WalletManager walletManager;

    public VaultAdapter() {
        this.walletManager = new WalletManager();
    }

    @Override
    public void run() {
        for (Player player : WIIC.INSTANCE.getServer().getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == Material.GLOWSTONE_DUST) {
                    if (item.hasItemMeta()) {
                        if (WalletUtil.hasWalletData(item)) {
                            Economy economy = WIIC.getEcon();
                            if (economy.hasAccount(player.getUniqueId())) {
                                if (VaultUtil.getBalance(player.getUniqueId()).join() == 0) {
                                    WalletData walletData = walletManager.getWallet(WalletUtil.getWalletId(item));
                                    if (!WalletUtil.wasBound(item)) {
                                        WalletUtil.bindWallet(item);
                                        VaultUtil.deposit(player.getUniqueId(), walletData.getTotalCoppets());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
