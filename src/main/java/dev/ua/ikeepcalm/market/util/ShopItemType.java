package dev.ua.ikeepcalm.market.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public enum ShopItemType {
    BLOCK,
    TOOL,
    ENCHANTED_BOOK,
    MAGIC_RECIPE,
    MAGIC_CHARACTERISTIC,
    MAGIC_POTION,
    OTHER;

    public static ShopItemType getType(ItemStack item) {
        if (item.getType().isBlock()) {
            return BLOCK;
        }
        if (item.getType().getMaxDurability() > 0) {
            return TOOL;
        }
        if (item.getType() == Material.ENCHANTED_BOOK) {
            return ENCHANTED_BOOK;
        }
        if (item.hasItemMeta()) {
            return switch (item.getType()) {
                case WRITTEN_BOOK -> MAGIC_RECIPE;
                case PLAYER_HEAD -> MAGIC_CHARACTERISTIC;
                case POTION -> MAGIC_POTION;
                default -> OTHER;
            };
        }
        return OTHER;
    }
}
