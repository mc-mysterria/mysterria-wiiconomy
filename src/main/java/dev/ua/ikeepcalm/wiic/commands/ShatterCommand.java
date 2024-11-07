package dev.ua.ikeepcalm.wiic.commands;

import de.tr7zw.nbtapi.NBTItem;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
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
            if (!item.getType().isAir() && item.getType() == Material.GOLD_INGOT) {
                NBTItem nbtItem = new NBTItem(item);
                if (nbtItem.hasTag("type")) {
                    switch (nbtItem.getString("type")) {
                        case "verlDor" -> {
                            if (item.getAmount() > 1) {
                                player.sendMessage(Component.text("Візьміть одну штуку у головну руку!").color(NamedTextColor.RED));
                                return true;
                            }
                            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                            player.getInventory().addItem(CoinUtil.getLick(64));
                            player.sendMessage(Component.text("Предмет розбитий!").color(NamedTextColor.GREEN));
                        }
                        case "lick" -> {
                            if (item.getAmount() > 1) {
                                player.sendMessage(Component.text("Цей предмет не може бути розбитий!").color(NamedTextColor.RED));
                                return true;
                            }
                            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                            player.getInventory().addItem(CoinUtil.getCoppet(64));
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
        }
        return true;
    }
}
