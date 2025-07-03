package dev.ua.ikeepcalm.wiic;

import dev.ua.ikeepcalm.wiic.commands.economy.BindCommand;
import dev.ua.ikeepcalm.wiic.commands.economy.ShatterCommand;
import dev.ua.ikeepcalm.wiic.commands.economy.WalletCommand;
import dev.ua.ikeepcalm.wiic.commands.shop.OpenShopCommand;
import dev.ua.ikeepcalm.wiic.commands.shop.ReloadShopsCommand;
import dev.ua.ikeepcalm.wiic.commands.shop.SaveShopItemCommand;
import dev.ua.ikeepcalm.wiic.economy.vault.VaultAdapter;
import dev.ua.ikeepcalm.wiic.listeners.VillagerListener;
import dev.ua.ikeepcalm.wiic.listeners.WalletListener;
import dev.ua.ikeepcalm.wiic.utils.common.LogWriter;
import dev.ua.ikeepcalm.wiic.utils.item.ShopItemsUtil;
import dev.ua.ikeepcalm.wiic.currency.services.WalletManager;
import dev.ua.ikeepcalm.wiic.currency.models.WalletRecipe;
import lombok.Getter;
import lombok.Setter;
import net.luckperms.api.LuckPerms;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

@Getter
@Setter
public final class WIIC extends JavaPlugin {

    public static WIIC INSTANCE;

    @Getter
    private ShopItemsUtil shopItemsUtil;
    @Getter
    private static LuckPerms luckPerms;
    private static String pluginNamespace;

    @Getter
    private static Economy econ = null;
    private VaultAdapter vaultAdapter;

    @Getter
    private WalletListener walletListener;

    @Getter
    private LogWriter conversionLogWriter;

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
        Objects.requireNonNull(getCommand("bind")).setExecutor(new BindCommand(new WalletManager()));
        Objects.requireNonNull(getCommand("save-shop-item")).setExecutor(new SaveShopItemCommand());
        Objects.requireNonNull(getCommand("open-shop")).setExecutor(new OpenShopCommand());
        Objects.requireNonNull(getCommand("reload-shops")).setExecutor(new ReloadShopsCommand());

        shopItemsUtil = new ShopItemsUtil(this);
        setupLuckPerms();
        if (!setupEconomy()) {
            getLogger().severe(String.format("[%s] - Disabled due to no Vault dependency found!", getDescription().getName()));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

// Enable if
//        vaultAdapter = new VaultAdapter();
//        vaultAdapter.runTaskTimer(this, 0, 100);

        conversionLogWriter = new LogWriter(new File(getDataFolder(), "conversions.log"));
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
        try {
            conversionLogWriter.close();
        } catch (IOException e) {
            getLogger().severe("Error closing conversionLogWriter");
            e.printStackTrace();
        }
        getLogger().info("WIIC plugin disabled...");
    }

    private void registerEvents(Listener... listeners) {
        PluginManager pl = this.getServer().getPluginManager();
        for (Listener listener : listeners) {
            pl.registerEvents(listener, this);
        }
    }

    private void setupLuckPerms() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
        }
    }

    public static String getNamespace() {
        return pluginNamespace;
    }

}
