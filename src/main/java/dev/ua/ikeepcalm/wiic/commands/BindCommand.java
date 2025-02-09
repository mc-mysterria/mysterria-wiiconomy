package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class BindCommand implements CommandExecutor {

    private final WalletManager walletManager;

    public BindCommand(WalletManager walletManager) {
        this.walletManager = walletManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player && command.getName().equalsIgnoreCase("bind")) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (WalletUtil.isWallet(item)) {
                if (item != null && item.getType() == Material.GLOWSTONE_DUST) {
                    if (item.hasItemMeta()) {
                        if (WalletUtil.hasWalletData(item)) {
                            Economy economy = WIIC.getEcon();
                            if (economy.hasAccount(player.getUniqueId())) {
                                if (VaultUtil.getBalance(player.getUniqueId()) == 0) {
                                    WalletData walletData = walletManager.getWallet(WalletUtil.getWalletId(item));
                                    if (!WalletUtil.wasBound(item)) {
                                        WalletUtil.bindWallet(item);
                                        VaultUtil.deposit(player.getUniqueId(), walletData.getTotalCoppets());
                                        player.sendMessage("§aГаманець прив'язаний!");
                                    }
                                } else {
                                    player.sendMessage("§cВи вже маєте гаманець!");
                                }
                            }
                        } else {
                            player.sendMessage("§cЦей гаманець вже був прив'язаний!");
                        }
                    }
                }
            } else {
                player.sendMessage("§cЦей предмет не є гаманцем!");
            }
        }
        return true;
    }
}
