package dev.ua.ikeepcalm.wiic.commands.shop;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class SaveShopItemCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Команда працює лише для гравців.").color(NamedTextColor.RED));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Неправильне використання.").color(NamedTextColor.RED));
            return false;
        }

        final ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().equals(Material.AIR)) {
            player.sendMessage(Component.text("Візьміть потрібний предмет у руку!").color(NamedTextColor.RED));
            return true;
        }

        WIIC.INSTANCE.getShopItemsUtil().addItem(args[0], item);
        player.sendMessage(Component.text("Предмет успішно збережено!").color(NamedTextColor.GREEN));

        return true;
    }
}
