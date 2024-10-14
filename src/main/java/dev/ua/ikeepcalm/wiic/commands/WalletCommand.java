package dev.ua.ikeepcalm.wiic.commands;

import de.tr7zw.nbtapi.NBTItem;
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
            if (!item.getType().isAir() && item.getType() == Material.GLOWSTONE_DUST) {
                NBTItem nbtItem = new NBTItem(item);
                if (nbtItem.getString("type").equals("wallet")) {
                    player.sendMessage("§aDEBUG UUID: " + nbtItem.getUUID("id"));
                    player.sendMessage("§aDEBUG OWNER: " + nbtItem.getString("owner"));
                    player.sendMessage("§aПовідомьте про ці данні адміністратору!");
                } else {
                    player.sendMessage("§cЦей предмет не є гаманцем!");
                }
            }
        }
        return true;
    }
}
