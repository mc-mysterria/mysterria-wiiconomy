package dev.ua.ikeepcalm.wiic;

import dev.ua.ikeepcalm.market.auction.listeners.JoinListener;
import dev.ua.ikeepcalm.market.npc.commands.SpawnNpcCommand;
import dev.ua.ikeepcalm.market.npc.listeners.NpcListener;
import dev.ua.ikeepcalm.market.util.AuctionUtil;
import dev.ua.ikeepcalm.market.util.PendingMoneyManager;
import dev.ua.ikeepcalm.wiic.commands.*;
import dev.ua.ikeepcalm.wiic.economy.VaultAdapter;
import dev.ua.ikeepcalm.wiic.listeners.VillagerListener;
import dev.ua.ikeepcalm.wiic.listeners.WalletListener;
import dev.ua.ikeepcalm.wiic.utils.ShopItemsUtil;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletRecipe;
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
    private AuctionUtil auctionUtil;
    @Getter
    private ShopItemsUtil shopItemsUtil;
    @Getter
    private static LuckPerms luckPerms;
    @Getter
    private static PendingMoneyManager pendingMoneyManager;
    private static String pluginNamespace;

    // Vault implementation
    @Getter
    private static Economy econ = null;
    private VaultAdapter vaultAdapter;

    @Override
    public void onEnable() {
        INSTANCE = this;
        pluginNamespace = new NamespacedKey(this, "dummy").getNamespace();

        getLogger().info("WIIC plugin enabled...");
        if (!new File(getDataFolder() + File.separator + "config.yml").exists()) {
            saveDefaultConfig();
        }
        new WalletRecipe(this);
        NpcListener npcListener = new NpcListener();
        registerEvents(new WalletListener(), new VillagerListener(this), npcListener, new JoinListener());
        Objects.requireNonNull(getCommand("wallet")).setExecutor(new WalletCommand());
        Objects.requireNonNull(getCommand("shatter")).setExecutor(new ShatterCommand());
        Objects.requireNonNull(getCommand("shopkeeper")).setExecutor(new SpawnNpcCommand(npcListener));
        Objects.requireNonNull(getCommand("bind")).setExecutor(new BindCommand(new WalletManager()));
        Objects.requireNonNull(getCommand("save-shop-item")).setExecutor(new SaveShopItemCommand());
        Objects.requireNonNull(getCommand("open-shop")).setExecutor(new OpenShopCommand());

        try {
            this.auctionUtil = new AuctionUtil(this.getDataFolder().toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
        pendingMoneyManager = new PendingMoneyManager(this);
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
        auctionUtil.close();
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
