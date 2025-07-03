package dev.ua.ikeepcalm.wiic.economy.models;

import lombok.Getter;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public record Shop(@Getter int rows, @Getter String title, @Getter List<ShopItem> items) {

    public static Shop fromConfig(Configuration config) {
        final ConfigurationSection itemsConfig = config.getConfigurationSection("items");
        final List<ShopItem> items = new ArrayList<>();
        if (itemsConfig != null) {
            for (String itemKey : itemsConfig.getKeys(false)) {
                items.add(ShopItem.fromConfig(itemsConfig, itemKey));
            }
        }
        return new Shop(
                config.getInt("rows"),
                config.getString("title"),
                items
        );
    }
}
