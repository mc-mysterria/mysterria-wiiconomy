package dev.ua.ikeepcalm.market.util;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class PendingMoneyManager {
    private final WIIC plugin;
    private final File pendingMoneyFile;
    private final FileConfiguration pendingMoneyConfig;

    public PendingMoneyManager(WIIC plugin) {
        this.plugin = plugin;
        pendingMoneyFile = new File(plugin.getDataFolder(), "pendingMoney.yml");
        if (!pendingMoneyFile.exists()) {
            pendingMoneyFile.getParentFile().mkdirs();
            plugin.saveResource("pendingMoney.yml", false);
        }
        pendingMoneyConfig = YamlConfiguration.loadConfiguration(pendingMoneyFile);
    }

    public int getPendingMoney(UUID playerId) {
        return pendingMoneyConfig.getInt(playerId.toString(), 0);
    }

    public void setPendingMoney(UUID playerId, int amount) {
        pendingMoneyConfig.set(playerId.toString(), amount);
        savePendingMoney();
    }

    private void savePendingMoney() {
        try {
            pendingMoneyConfig.save(pendingMoneyFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
