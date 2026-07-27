package dev.ua.ikeepcalm.wiic.config;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.shop.model.source.ShopCategory;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads and exposes {@code shop.yml} — catalogue rules, prices, market
 * parameters, purchase limits, and GUI text. Reload with {@link #reload()}
 * (wired into {@code /wiic reload}).
 */
public final class ShopConfig {

    private final WIIC plugin;
    private final File file;
    private FileConfiguration config;

    public ShopConfig(WIIC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            plugin.saveResource("shop.yml", false);
        }
        reload();
    }

    /** Reloads {@code shop.yml} from disk, re-merging jar defaults for any keys the admin hasn't set. */
    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("shop.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load shop.yml defaults: " + e.getMessage());
        }
    }

    public FileConfiguration raw() {
        return config;
    }

    // -------------------------------------------------------------------------
    // Catalogue rules
    // -------------------------------------------------------------------------

    public Set<String> denyNames() {
        return new HashSet<>(config.getStringList("catalog.deny"));
    }

    public Set<String> allowNames() {
        return new HashSet<>(config.getStringList("catalog.allow"));
    }

    public List<Pattern> excludeNamePatterns() {
        return config.getStringList("catalog.exclude-name-patterns").stream()
                .map(Pattern::compile)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Pricing
    // -------------------------------------------------------------------------

    public int defaultPrice() {
        return Math.max(1, config.getInt("pricing.default-price", 4));
    }

    public int categoryPrice(ShopCategory category) {
        String path = "pricing.category-prices." + category.name();
        return config.contains(path) ? Math.max(1, config.getInt(path)) : defaultPrice();
    }

    public Integer override(Material material) {
        String path = "pricing.overrides." + material.name();
        return config.contains(path) ? Math.max(1, config.getInt(path)) : null;
    }

    /**
     * Longest-suffix-match multiplier for a material's "shape" (log vs. planks,
     * slab vs. full block, …) against {@code pricing.shape-multipliers}. Returns
     * {@code 1.0} if nothing matches.
     */
    public double shapeMultiplier(String materialName) {
        ConfigurationSection section = config.getConfigurationSection("pricing.shape-multipliers");
        if (section == null) return 1.0;
        return section.getKeys(false).stream()
                .filter(materialName::endsWith)
                .max(Comparator.comparingInt(String::length))
                .map(section::getDouble)
                .orElse(1.0);
    }

    // -------------------------------------------------------------------------
    // Market
    // -------------------------------------------------------------------------

    public int scanIntervalMinutes() {
        return Math.max(1, config.getInt("market.scan-interval-minutes", 10));
    }

    public boolean onlineSampleOnly() {
        return "online-sample".equalsIgnoreCase(config.getString("market.supply-source", "vault-scan"));
    }

    public double baseline() {
        return config.getDouble("market.baseline", 0);
    }

    public double elasticity() {
        return config.getDouble("market.elasticity", 0.75);
    }

    public double smoothingAlpha() {
        double v = config.getDouble("market.smoothing-alpha", 0.25);
        return Math.min(1.0, Math.max(0.01, v));
    }

    public double minMultiplier() {
        return config.getDouble("market.min-multiplier", 0.5);
    }

    public double maxMultiplier() {
        return config.getDouble("market.max-multiplier", 4.0);
    }

    // -------------------------------------------------------------------------
    // Limits
    // -------------------------------------------------------------------------

    public int maxPerPurchase() {
        return Math.max(1, config.getInt("limits.max-per-purchase", 3456));
    }

    public long cooldownMs() {
        return Math.max(0, config.getLong("limits.cooldown-ms", 500));
    }

    public long confirmArmMs() {
        return Math.max(0, config.getLong("limits.confirm-arm-ms", 400));
    }

    // -------------------------------------------------------------------------
    // Messages
    // -------------------------------------------------------------------------

    public String message(String key, String def) {
        return config.getString("messages." + key, def);
    }
}
