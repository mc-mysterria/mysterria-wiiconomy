package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.economy.Appraiser;
import dev.ua.ikeepcalm.wiic.economy.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.guis.WalletGUI;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

public class WalletCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender instanceof Player player && command.getName().equalsIgnoreCase("wallet")) {
            Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> openVaultInventory(player));
        }
        return true;
    }

    private void openVaultInventory(Player p) {
        if (WIIC.getEcon().hasAccount(p.getUniqueId())) {
            BigDecimal balance = WIIC.getEcon().balance("iConomyUnlocked", p.getUniqueId());
            WalletData data = new WalletData(balance.intValue());
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1, 1);
            Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> new WalletGUI(
                    new Appraiser(),
                    new WalletManager(),
                    new SoldItemsManager(WIIC.INSTANCE)
            ).open(p, data, () -> {
                WalletGUI.playersWithOpenWallets.remove(p);
            }));
        } else {
            p.sendMessage(Component.text("Не ініціалізовано. Потримай гаманець у руках декілька секунд, і спробуй ще раз!").color(NamedTextColor.RED));
        }
    }
}
