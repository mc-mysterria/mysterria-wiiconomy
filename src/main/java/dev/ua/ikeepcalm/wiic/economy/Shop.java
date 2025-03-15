package dev.ua.ikeepcalm.wiic.economy;

import lombok.Getter;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public class Shop {
    @Getter
    private final int rows;
    @Getter
    private final String title;
    @Getter
    private final List<ShopItem> items;

    public Shop(int rows, String title, List<ShopItem> items) {
        this.rows = rows;
        this.title = title;
        this.items = items;
    }

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
