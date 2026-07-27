package dev.ua.ikeepcalm.wiic.domain.shop.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.ShopConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The shop's money-sink regulator. Coppets spent in {@code /shop} are destroyed
 * (withdrawn, never redeposited); this class periodically measures the server's
 * total money supply and turns that into a price multiplier, so a wealthier server
 * pays more per block and a drained one pays less — the sink self-regulates instead
 * of needing to be hand-tuned as the economy grows.
 *
 * <pre>
 * raw      = (supply / baseline) ^ elasticity
 * smoothed = alpha * raw + (1 - alpha) * previous smoothed value      (EMA)
 * index    = clamp(smoothed, min-multiplier, max-multiplier)
 * </pre>
 *
 * <p>Scans run fully async on a timer ({@code market.scan-interval-minutes}) — summing
 * every account's balance is N calls into iConomyUnlocked and may be DB-backed, so it
 * must never block a GUI open or a purchase. The index and baseline are persisted to
 * {@code market-state.yml} so a restart doesn't reset the market to neutral.
 */
public final class MarketIndex {

    private final WIIC plugin;
    private final ShopConfig shopConfig;
    private final File file;

    private volatile double baseline;
    private volatile double smoothedIndex = 1.0;
    private volatile double lastSupply;
    private BukkitTask task;

    public MarketIndex(WIIC plugin, ShopConfig shopConfig) {
        this.plugin = plugin;
        this.shopConfig = shopConfig;
        this.file = new File(plugin.getDataFolder(), "market-state.yml");
        load();
    }

    /** Starts the periodic async scan. Call once during enable. */
    public void start() {
        stop();
        long periodTicks = shopConfig.scanIntervalMinutes() * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::scan, 20L * 10, periodTicks);
    }

    /** Cancels the periodic scan. Call during disable. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** The current smoothed, clamped price multiplier. Safe to call from any thread. */
    public double currentIndex() {
        return smoothedIndex;
    }

    public double currentSupply() {
        return lastSupply;
    }

    public double currentBaseline() {
        return baseline;
    }

    /** Runs one scan synchronously on the calling thread. Intended to be called off the main thread. */
    public void scan() {
        if (WIIC.getEcon() == null) return;

        double supply;
        try {
            supply = shopConfig.onlineSampleOnly() ? sampleOnlineSupply() : sampleFullSupply();
        } catch (Exception e) {
            plugin.getLogger().warning("Market supply scan failed: " + e.getMessage());
            return;
        }

        double configuredBaseline = shopConfig.baseline();
        double newBaseline = baseline;
        if (configuredBaseline > 0) {
            newBaseline = configuredBaseline;
        } else if (newBaseline <= 0) {
            newBaseline = Math.max(1, supply);
        }

        double raw = newBaseline > 0 ? Math.pow(supply / newBaseline, shopConfig.elasticity()) : 1.0;
        double alpha = shopConfig.smoothingAlpha();
        double next = alpha * raw + (1 - alpha) * smoothedIndex;
        double clamped = Math.min(shopConfig.maxMultiplier(), Math.max(shopConfig.minMultiplier(), next));

        baseline = newBaseline;
        smoothedIndex = clamped;
        lastSupply = supply;
        save();
    }

    private double sampleFullSupply() {
        double total = 0;
        for (UUID uuid : WIIC.getEcon().getUUIDNameMap().keySet()) {
            BigDecimal balance = WIIC.getEcon().balance("iConomyUnlocked", uuid);
            if (balance != null) total += balance.doubleValue();
        }
        return total;
    }

    private double sampleOnlineSupply() {
        double total = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            BigDecimal balance = WIIC.getEcon().balance("iConomyUnlocked", player.getUniqueId());
            if (balance != null) total += balance.doubleValue();
        }
        return total;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        baseline = config.getDouble("baseline", 0);
        smoothedIndex = config.getDouble("index", 1.0);
        lastSupply = config.getDouble("last-supply", 0);
    }

    private synchronized void save() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("baseline", baseline);
        config.set("index", smoothedIndex);
        config.set("last-supply", lastSupply);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save market-state.yml: " + e.getMessage());
        }
    }
}
