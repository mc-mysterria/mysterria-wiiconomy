package dev.ua.ikeepcalm.wiic.economy;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.math.BigDecimal;

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
                            WalletData walletData = walletManager.getWallet(WalletUtil.getWalletId(item));
                            Economy economy = WIIC.getEcon();
                            if (economy.hasAccount(player.getUniqueId())) {
                                BigDecimal balance = economy.balance("iConomyUnlocked", player.getUniqueId());
                                BigDecimal balanceInWallet = new BigDecimal(walletData.getTotalCoppets());
                                if (balanceInWallet.compareTo(balance) != 0) {
                                    economy.withdraw("iConomyUnlocked", player.getUniqueId(), balance);
                                    economy.deposit("iConomyUnlocked", player.getUniqueId(), balanceInWallet);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
