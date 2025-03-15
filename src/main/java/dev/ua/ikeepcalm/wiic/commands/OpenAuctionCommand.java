package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.market.auction.inventories.MarketInventory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class OpenAuctionCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] args) {
        if (args.length != 1) {
            sender.sendMessage(Component.text("Неправильне використання!").color(NamedTextColor.RED));
            return false;
        }

        final Player player = Bukkit.getPlayerExact(args[0]);
        if (player == null) {
            sender.sendMessage(Component.text("Гравця не знайдено!").color(NamedTextColor.RED));
            return true;
        }

        new MarketInventory().open(player);

        return true;
    }
}
