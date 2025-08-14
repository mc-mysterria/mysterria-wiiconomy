package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class ShatterCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player && command.getName().equalsIgnoreCase("shatter")) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (CoinUtil.isCoin(item)) {
                if (item.getAmount() > 1) {
                    WIIC.INSTANCE.getMessageManager().sendMessage(player, "wiic.commands.shatter.error.take_one_item");
                    return true;
                }
                final String type = ItemUtil.getType(item);
                if (type != null) {
                    switch (type) {
                        case "goldcoin" -> {
                            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                            player.getInventory().addItem(CoinUtil.getLick(64));
                            WIIC.INSTANCE.getMessageManager().sendMessage(player, "wiic.commands.shatter.success");
                        }
                        case "silvercoin" -> {
                            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                            player.getInventory().addItem(CoinUtil.getCoppet(64));
                            WIIC.INSTANCE.getMessageManager().sendMessage(player, "wiic.commands.shatter.success");
                        }
                        default -> {
                            WIIC.INSTANCE.getMessageManager().sendMessage(player, "wiic.commands.shatter.error.cannot_shatter");
                            return true;
                        }
                    }
                }
            } else {
                WIIC.INSTANCE.getMessageManager().sendMessage(player, "wiic.commands.shatter.error.cannot_shatter");
            }
        }
        return true;
    }
}
