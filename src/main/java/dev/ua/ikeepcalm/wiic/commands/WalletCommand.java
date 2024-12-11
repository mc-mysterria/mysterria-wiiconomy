package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class WalletCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player && command.getName().equalsIgnoreCase("wallet")) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (WalletUtil.isWallet(item)) {
                player.sendMessage("§aDEBUG UUID: " + WalletUtil.getWalletId(item));
                player.sendMessage("§aDEBUG OWNER: " + WalletUtil.getWalletOwner(item));
                player.sendMessage("§aПовідомьте про ці данні адміністратору!");
            } else {
                player.sendMessage("§cЦей предмет не є гаманцем!");
            }
        }
        return true;
    }
}
