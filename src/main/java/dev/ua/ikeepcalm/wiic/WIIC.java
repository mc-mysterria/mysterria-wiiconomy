package dev.ua.ikeepcalm.wiic;

import dev.ua.ikeepcalm.wiic.commands.ShatterCommand;
import dev.ua.ikeepcalm.wiic.commands.ShopCommand;
import dev.ua.ikeepcalm.wiic.commands.WalletCommand;
import dev.ua.ikeepcalm.wiic.commands.WiicCommand;
import dev.ua.ikeepcalm.wiic.domain.wallet.models.WalletRecipe;
import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.listeners.VillagerListener;
import dev.ua.ikeepcalm.wiic.listeners.WalletListener;
import dev.ua.ikeepcalm.wiic.domain.shop.service.MarketIndex;
import dev.ua.ikeepcalm.wiic.domain.shop.service.PurchaseService;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopCatalog;
import dev.ua.ikeepcalm.wiic.config.ShopConfig;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopPricing;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
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
    private ShopServices shopServices;

    public static String getNamespace() {
        return pluginNamespace;
    }

    @Override
    public void onEnable() {
        INSTANCE = this;
        pluginNamespace = new NamespacedKey(this, "dummy").getNamespace();

        getLogger().info("WIIC plugin enabled...");
        if (!new File(getDataFolder() + File.separator + "config.yml").exists()) {
            saveDefaultConfig();
        }
        WalletConfig.init();
        new WalletRecipe(this);
        walletListener = new WalletListener();
        registerEvents(walletListener, new VillagerListener(this));
        Objects.requireNonNull(getCommand("wallet")).setExecutor(new WalletCommand());
        Objects.requireNonNull(getCommand("shatter")).setExecutor(new ShatterCommand());
        WiicCommand wiicCommand = new WiicCommand(this);
        Objects.requireNonNull(getCommand("wiic")).setExecutor(wiicCommand);
        Objects.requireNonNull(getCommand("wiic")).setTabCompleter(wiicCommand);

        shopServices = buildShopServices();
        Objects.requireNonNull(getCommand("shop")).setExecutor(new ShopCommand(shopServices));
        shopServices.marketIndex().start();

        if (!setupEconomy()) {
            getLogger().severe(String.format("[%s] - Disabled due to no Vault dependency found!", getDescription().getName()));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private ShopServices buildShopServices() {
        ShopConfig shopConfig = new ShopConfig(this);
        ShopCatalog catalog = new ShopCatalog(shopConfig);
        MarketIndex marketIndex = new MarketIndex(this, shopConfig);
        ShopPricing pricing = new ShopPricing(catalog, marketIndex);
        PurchaseService purchaseService = new PurchaseService(this, shopConfig, catalog, pricing, marketIndex);
        return new ShopServices(shopConfig, catalog, pricing, marketIndex, purchaseService);
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<net.milkbowl.vault2.economy.Economy> rsp = getServer().getServicesManager().getRegistration(net.milkbowl.vault2.economy.Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    @Override
    public void onDisable() {
        if (shopServices != null) shopServices.marketIndex().stop();
        getLogger().info("WIIC plugin disabled...");
    }

    private void registerEvents(Listener... listeners) {
        PluginManager pl = this.getServer().getPluginManager();
        for (Listener listener : listeners) {
            pl.registerEvents(listener, this);
        }
    }

}
