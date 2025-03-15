package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.guis.ShopGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class ReloadShopsCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] args) {
        ShopGUI.clearShopCache();
        sender.sendMessage(Component.text("Магазини успішно перезавантажено.").color(NamedTextColor.GREEN));

        return true;
    }
}
