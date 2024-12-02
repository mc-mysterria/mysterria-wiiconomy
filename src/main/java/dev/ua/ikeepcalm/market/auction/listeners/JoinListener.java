package dev.ua.ikeepcalm.market.auction.listeners;

import dev.ua.ikeepcalm.market.util.AuctionUtil;
import dev.ua.ikeepcalm.market.util.PendingMoneyManager;
import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        int amount = WIIC.getPendingMoneyManager().getPendingMoney(event.getPlayer().getUniqueId());
        if (amount > 0) {
            event.getPlayer().sendMessage(Component.text("Ви заробили " + AuctionUtil.getFormattedPrice(amount) + "! Зніміть кошти у найближчого торговця.", NamedTextColor.GREEN));
        }
    }
}
