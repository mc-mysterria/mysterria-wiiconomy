package dev.ua.ikeepcalm.wiic;

import dev.ua.ikeepcalm.wiic.commands.ShatterCommand;
import dev.ua.ikeepcalm.wiic.commands.WalletCommand;
import dev.ua.ikeepcalm.wiic.currency.models.WalletRecipe;
import dev.ua.ikeepcalm.wiic.listeners.VillagerListener;
import dev.ua.ikeepcalm.wiic.listeners.WalletListener;
import dev.ua.ikeepcalm.wiic.locale.MessageManager;
import lombok.Getter;
import lombok.Setter;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

@Getter
@Setter
public final class WIIC extends JavaPlugin {

    public static WIIC INSTANCE;
    private static String pluginNamespace;

    @Getter
    private static Economy econ = null;

    @Getter
    private WalletListener walletListener;

    @Getter
    private MessageManager messageManager;

    @Override
    public void onEnable() {
        INSTANCE = this;
        pluginNamespace = new NamespacedKey(this, "dummy").getNamespace();

        getLogger().info("WIIC plugin enabled...");
        if (!new File(getDataFolder() + File.separator + "config.yml").exists()) {
            saveDefaultConfig();
        }
        new WalletRecipe(this);
        walletListener = new WalletListener();
        registerEvents(walletListener, new VillagerListener(this));
        Objects.requireNonNull(getCommand("wallet")).setExecutor(new WalletCommand());
        Objects.requireNonNull(getCommand("shatter")).setExecutor(new ShatterCommand());

        if (!setupEconomy()) {
            getLogger().severe(String.format("[%s] - Disabled due to no Vault dependency found!", getDescription().getName()));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        messageManager = new MessageManager(this);
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    @Override
    public void onDisable() {
        getLogger().info("WIIC plugin disabled...");
    }

    private void registerEvents(Listener... listeners) {
        PluginManager pl = this.getServer().getPluginManager();
        for (Listener listener : listeners) {
            pl.registerEvents(listener, this);
        }
    }

    public static String getNamespace() {
        return pluginNamespace;
    }

}
