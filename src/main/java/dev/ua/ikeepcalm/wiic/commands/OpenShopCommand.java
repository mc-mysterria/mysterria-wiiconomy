package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.guis.ShopGUI;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class OpenShopCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] args) {
        if (args.length != 2) {
            sender.sendMessage("Неправильне використання!");
            return false;
        }

        final Player player = Bukkit.getPlayerExact(args[0]);
        if (player == null) {
            sender.sendMessage("Гравця не знайдено.");
            return true;
        }

        new ShopGUI(WIIC.INSTANCE, player, args[1]).open(false);

        return true;
    }
}
