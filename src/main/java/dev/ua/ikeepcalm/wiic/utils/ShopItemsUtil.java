package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ShopItemsUtil {
    private final WIIC plugin;
    private final File file;
    private final Map<String, ItemStack> items = new HashMap<>();

    public ShopItemsUtil(WIIC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shopItems.yml");

        loadItems();
    }

    public void addItem(String id, ItemStack item) {
        items.put(id, item.clone());
        saveItems();
    }

    public ItemStack getItem(String id) {
        final ItemStack item = items.get(id);
        if (item == null) return null;
        return item.clone();
    }

    private void loadItems() {
        final Configuration config = YamlConfiguration.loadConfiguration(file);

        items.clear();

        for (String key : config.getKeys(false)) {
            items.put(key, ItemStack.deserializeBytes(Base64.getDecoder().decode(config.getString(key))));
        }
    }

    private void saveItems() {
        final YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, ItemStack> entry : items.entrySet()) {
            config.set(entry.getKey(), Base64.getEncoder().encodeToString(entry.getValue().serializeAsBytes()));
        }

        try {
            config.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
