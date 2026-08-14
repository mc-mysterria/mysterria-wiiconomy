package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
import dev.ua.ikeepcalm.wiic.gui.shop.ShopGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
        // With the Underground Market active, the admin shop is physical-only:
        // regular players are pointed at the market instead of getting the GUI.
        var market = WIIC.INSTANCE.getMarketModule();
        if (market != null && !player.hasPermission("wiic.shop.legacy")) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    market.getConfig().message("shop-moved",
                            "<gray>The builders' market has gone underground. Seek the hidden entrance...")));
            return true;
        }
        new ShopGUI(services).open(player);
        return true;
    }
}
