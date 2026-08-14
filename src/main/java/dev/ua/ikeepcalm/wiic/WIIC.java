package dev.ua.ikeepcalm.wiic;

import dev.ua.ikeepcalm.wiic.commands.*;
import dev.ua.ikeepcalm.wiic.config.ShopConfig;
import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketModule;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopCatalog;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopPricing;
import dev.ua.ikeepcalm.wiic.domain.shop.model.MarketIndex;
import dev.ua.ikeepcalm.wiic.domain.shop.service.PurchaseService;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
import dev.ua.ikeepcalm.wiic.domain.wallet.models.WalletRecipe;
import dev.ua.ikeepcalm.wiic.listeners.VillagerListener;
import dev.ua.ikeepcalm.wiic.listeners.WalletListener;
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
public class WIIC extends JavaPlugin {

    public static WIIC INSTANCE;

    private static String pluginNamespace;

    @Getter
    private static Economy econ = null;

    @Getter
    private WalletListener walletListener;

    @Getter
    private ShopServices shopServices;

    @Getter
    private MarketModule marketModule;

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
            return;
        }

        // Underground Market — optional module; needs Vault, so wired after setupEconomy().
        marketModule = MarketModule.enableIfConfigured(this);
        if (marketModule != null) {
            MarketAdminCommand marketCommand = new MarketAdminCommand(marketModule);
            Objects.requireNonNull(getCommand("wiicmarket")).setExecutor(marketCommand);
            Objects.requireNonNull(getCommand("wiicmarket")).setTabCompleter(marketCommand);
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
        if (marketModule != null) marketModule.shutdown();
        if (shopServices != null) shopServices.marketIndex().stop();
        // Static, so it survives a plugin reload. A purchase whose callback was dropped
        // during shutdown would otherwise leave that player permanently unable to buy.
        PurchaseService.releaseAll();
        getLogger().info("WIIC plugin disabled...");
    }

    private void registerEvents(Listener... listeners) {
        PluginManager pl = this.getServer().getPluginManager();
        for (Listener listener : listeners) {
            pl.registerEvents(listener, this);
        }
    }

}
