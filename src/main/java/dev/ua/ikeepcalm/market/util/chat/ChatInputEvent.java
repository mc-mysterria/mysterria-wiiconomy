package dev.ua.ikeepcalm.market.util.chat;

import org.bukkit.entity.Player;

public record ChatInputEvent(Player player, String message) {
}
