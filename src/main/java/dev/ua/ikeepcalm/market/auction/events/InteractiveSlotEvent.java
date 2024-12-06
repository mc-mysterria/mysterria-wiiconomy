package dev.ua.ikeepcalm.market.auction.events;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public record InteractiveSlotEvent(Player player, ItemStack item) {
}
