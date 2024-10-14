package dev.ua.ikeepcalm.wiic;

import dev.ua.ikeepcalm.wiic.commands.ConvertCommand;
import dev.ua.ikeepcalm.wiic.commands.ShatterCommand;
import dev.ua.ikeepcalm.wiic.commands.WalletCommand;
import dev.ua.ikeepcalm.wiic.listeners.WalletListener;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletRecipe;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

public final class WIIC extends JavaPlugin {

    public static WIIC INSTANCE;

    @Override
    public void onEnable() {
        INSTANCE = this;
        getLogger().info("WIIC plugin enabled...");
        if (!new File(getDataFolder() + File.separator + "config.yml").exists()) {
            saveDefaultConfig();
        }
        new WalletRecipe(this);
        registerEvents(new WalletListener());
        Objects.requireNonNull(getCommand("wallet")).setExecutor(new WalletCommand());
        Objects.requireNonNull(getCommand("convert")).setExecutor(new ConvertCommand());
        Objects.requireNonNull(getCommand("shatter")).setExecutor(new ShatterCommand());
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
}
