package dev.ua.ikeepcalm.wiic.domain.wallet.services;

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

    /**
     * Every method is synchronized on this manager. Selling runs on the async scheduler
     * pool, so two players selling on the same tick land here on different threads — an
     * unguarded read-modify-write would drop one player's tally (letting them sell past the
     * daily limit), and two concurrent saves can leave a half-written sold-items.yml.
     */
    public synchronized void setSoldValue(Player player, int value) {
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

    /**
     * Adds to today's tally as one indivisible step, and reports the new total. Callers must
     * use this rather than get-then-set: the gap between the two is exactly where a second
     * sale slips through uncounted.
     */
    public synchronized int addSoldValue(Player player, int delta) {
        int updated = getSoldValue(player) + delta;
        setSoldValue(player, updated);
        return updated;
    }

    public synchronized int getSoldValue(Player player) {
        return soldItemsConfig.getInt(player.getUniqueId() + "." + getCurrentDate(), 0);
    }

    public synchronized int getAvailableSellingAmount(Player player) {
        return plugin.getConfig().getInt("daily-limit", 0) - getSoldValue(player);
    }

    private void saveSoldItems() {
        try {
            soldItemsConfig.save(soldItemsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save sold-items.yml: " + e.getMessage());
        }
    }

    private String getCurrentDate() {
        return LocalDate.now().toString();
    }
}
