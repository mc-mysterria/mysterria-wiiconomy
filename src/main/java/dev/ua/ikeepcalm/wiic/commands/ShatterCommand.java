package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.utils.CoinType;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            final CoinType coinType = CoinUtil.getCoinType(item);
            if (coinType != CoinType.NONE) {
                if (item.getAmount() > 1) {
                    player.sendMessage(Component.text("Візьміть одну штуку у головну руку!").color(NamedTextColor.RED));
                    return true;
                }
                final String type = ItemUtil.getType(item);
                switch (type) {
                    case "verlDor", "goldcoin" -> {
                        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                        player.getInventory().addItem(CoinUtil.getLick(64));
                        if (coinType == CoinType.OLD) WalletUtil.logConversion(player, type, 1);
                        player.sendMessage(Component.text("Предмет розбитий!").color(NamedTextColor.GREEN));
                    }
                    case "lick", "silvercoin" -> {
                        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                        player.getInventory().addItem(CoinUtil.getCoppet(64));
                        if (coinType == CoinType.OLD) WalletUtil.logConversion(player, type, 1);
                        player.sendMessage(Component.text("Предмет розбитий!").color(NamedTextColor.GREEN));
                    }
                    default -> {
                        player.sendMessage(Component.text("Цей предмет не може бути розбитий!").color(NamedTextColor.RED));
                        return true;
                    }
                }
            } else {
                player.sendMessage(Component.text("Цей предмет не може бути розбитий!").color(NamedTextColor.RED));
            }
        }
        return true;
    }
}
