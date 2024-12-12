package dev.ua.ikeepcalm.wiic;

import dev.ua.ikeepcalm.market.auction.listeners.JoinListener;
import dev.ua.ikeepcalm.market.npc.commands.SpawnNpcCommand;
import dev.ua.ikeepcalm.market.npc.listeners.NpcListener;
import dev.ua.ikeepcalm.market.util.AuctionUtil;
import dev.ua.ikeepcalm.market.util.PendingMoneyManager;
import dev.ua.ikeepcalm.wiic.commands.ShatterCommand;
import dev.ua.ikeepcalm.wiic.commands.WalletCommand;
import dev.ua.ikeepcalm.wiic.listeners.WalletListener;
import dev.ua.ikeepcalm.wiic.listeners.VillagerListener;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletRecipe;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public final class WIIC extends JavaPlugin {

    public static WIIC INSTANCE;
    private AuctionUtil auctionUtil;
    private static LuckPerms luckPerms;
    private static PendingMoneyManager pendingMoneyManager;

    @Override
    public void onEnable() {
        INSTANCE = this;
        getLogger().info("WIIC plugin enabled...");
        if (!new File(getDataFolder() + File.separator + "config.yml").exists()) {
            saveDefaultConfig();
        }
        new WalletRecipe(this);
        NpcListener npcListener = new NpcListener();
        registerEvents(new WalletListener(), new VillagerListener(this), npcListener, new JoinListener());
        Objects.requireNonNull(getCommand("wallet")).setExecutor(new WalletCommand());
        Objects.requireNonNull(getCommand("shatter")).setExecutor(new ShatterCommand());
        Objects.requireNonNull(getCommand("summon-shopkeeper")).setExecutor(new SpawnNpcCommand(npcListener));

        try {
            this.auctionUtil = new AuctionUtil(this.getDataFolder().toPath());
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        setupLuckPerms();
        pendingMoneyManager = new PendingMoneyManager(this);
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

    public AuctionUtil getAuctionUtil() {
        return this.auctionUtil;
    }

    public static LuckPerms getLuckPerms(){
        return luckPerms;
    }

    public static PendingMoneyManager getPendingMoneyManager() { return pendingMoneyManager; }
}
