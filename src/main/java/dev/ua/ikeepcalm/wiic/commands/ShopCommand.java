package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.gui.shop.ShopGUI;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ShopCommand implements CommandExecutor {

    private final ShopServices services;

    public ShopCommand(ShopServices services) {
        this.services = services;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED));
            return true;
        }
        new ShopGUI(services).open(player);
        return true;
    }
}
