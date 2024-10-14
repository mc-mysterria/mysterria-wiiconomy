package dev.ua.ikeepcalm.wiic.commands;

import de.tr7zw.nbtapi.NBTItem;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class ConvertCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player && command.getName().equalsIgnoreCase("convert")) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (!item.getType().isAir() && item.getType() == Material.GLOWSTONE_DUST) {
                NBTItem nbtItem = new NBTItem(item);
                if (nbtItem.getString("type").equals("wallet")) {
                    UUID id = nbtItem.getUUID("id");
                    WalletManager walletManager = new WalletManager();
                    if (id == null || walletManager.getWallet(id) == null) {
                        walletManager.createWallet(id, player.getName());
                        player.sendMessage(Component.text("Успішно конвертовано!").color(NamedTextColor.GREEN));
                        return true;
                    } else {
                        player.sendMessage(Component.text("Цей гаманець вже конвертовано!").color(NamedTextColor.RED));
                    }
                } else {
                    player.sendMessage("§cЦей предмет не є гаманцем!");
                }
            }
        }
        return true;
    }
}
