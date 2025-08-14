package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class WalletCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player && command.getName().equalsIgnoreCase("wallet")) {
            WIIC.INSTANCE.getWalletListener().startOpeningVault(player);
        }
        return true;
    }
}
