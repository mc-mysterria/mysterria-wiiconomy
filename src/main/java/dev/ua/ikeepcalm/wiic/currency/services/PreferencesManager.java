package dev.ua.ikeepcalm.wiic.currency.services;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Persists per-player GUI theme preferences to {@code preferences.yml}
 * in the plugin data folder. Call {@link #init()} once during plugin enable.
 */
public final class PreferencesManager {

    private static File file;
    private static YamlConfiguration config;

    private PreferencesManager() {}

    public static void init() {
        file = new File(WIIC.INSTANCE.getDataFolder(), "preferences.yml");
        config = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
    }

    /**
     * Returns the saved theme ID for the player, or an empty string if none is set.
     */
    public static String getTheme(UUID playerId) {
        return config.getString(playerId.toString(), "");
    }

    /**
     * Returns a string value from the player's active theme config section.
     * Falls back to {@code fallback} if no theme is saved, the key is missing, or the value is empty.
     */
    public static String getThemeString(UUID playerId, String key, String fallback) {
        String themeId = getTheme(playerId);
        if (themeId.isEmpty()) return fallback;
        ConfigurationSection theme = WIIC.INSTANCE.getConfig()
                .getConfigurationSection("settings-gui.themes." + themeId);
        if (theme == null) return fallback;
        String value = theme.getString(key);
        return (value != null && !value.isEmpty()) ? value : fallback;
    }

    /**
     * Returns the background {@link Material} from the player's active theme.
     * Falls back to {@code fallback} if no theme is saved, the theme section is missing,
     * or the {@code background} key is absent / invalid.
     */
    public static Material getThemeBackground(UUID playerId, Material fallback) {
        String themeId = getTheme(playerId);
        if (themeId.isEmpty()) return fallback;
        ConfigurationSection theme = WIIC.INSTANCE.getConfig()
                .getConfigurationSection("settings-gui.themes." + themeId);
        if (theme == null) return fallback;
        String name = theme.getString("background");
        if (name == null || name.isEmpty()) return fallback;
        Material m = Material.matchMaterial(name);
        return m != null ? m : fallback;
    }

    /**
     * Saves the player's chosen theme and writes the file to disk immediately.
     */
    public static void setTheme(UUID playerId, String themeId) {
        config.set(playerId.toString(), themeId);
        try {
            config.save(file);
        } catch (IOException e) {
            WIIC.INSTANCE.getLogger().warning("Failed to save player preferences: " + e.getMessage());
        }
    }
}
