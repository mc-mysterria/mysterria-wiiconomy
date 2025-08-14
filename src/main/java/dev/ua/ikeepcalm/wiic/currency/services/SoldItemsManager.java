package dev.ua.ikeepcalm.wiic.currency.services;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;

public class SoldItemsManager {
    private final WIIC plugin;
    private final File soldItemsFile;
    private final FileConfiguration soldItemsConfig;

    public SoldItemsManager(WIIC plugin) {
        this.plugin = plugin;
        soldItemsFile = new File(plugin.getDataFolder(), "sold-items.yml");
        if (!soldItemsFile.exists()) {
            soldItemsFile.getParentFile().mkdirs();
            plugin.saveResource("sold-items.yml", false);
        }
        soldItemsConfig = YamlConfiguration.loadConfiguration(soldItemsFile);
    }

    public void setSoldValue(Player player, int value) {
        String currentDate = getCurrentDate();
        ConfigurationSection playerSection = soldItemsConfig.getConfigurationSection(player.getUniqueId().toString());
        if (playerSection != null) {
            for (String date : playerSection.getKeys(false)) {
                if (!date.equals(currentDate)) {
                    playerSection.set(date, null);
                }
            }
            playerSection.set(currentDate, value);
        } else {
            soldItemsConfig.set(player.getUniqueId() + "." + currentDate, value);
        }
        saveSoldItems();
    }

    public int getSoldValue(Player player) {
        return soldItemsConfig.getInt(player.getUniqueId() + "." + getCurrentDate(), 0);
    }

    public int getAvailableSellingAmount(Player player) {
        return plugin.getConfig().getInt("dailyLimit", 0) - getSoldValue(player);
    }

    private void saveSoldItems() {
        try {
            soldItemsConfig.save(soldItemsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getCurrentDate() {
        return LocalDate.now().toString();
    }
}
