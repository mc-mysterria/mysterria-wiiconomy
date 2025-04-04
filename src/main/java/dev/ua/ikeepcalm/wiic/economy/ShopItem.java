package dev.ua.ikeepcalm.wiic.economy;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public record ShopItem(ItemStack item, int price, String description, int coppetPrice) {
    public static ShopItem fromConfig(ConfigurationSection config, String prefix) {
        return new ShopItem(
                WIIC.INSTANCE.getShopItemsUtil().getItem(config.getString(prefix + ".id")),
                config.getInt(prefix + ".price"),
                config.getString(prefix + ".description"),
                config.getInt(prefix + ".coppet-price", -1)
        );
    }
}
