package dev.ua.ikeepcalm.wiic.locale;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.TranslationStore;
import net.kyori.adventure.util.UTF8ResourceBundleControl;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class MessageManager {

    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
    private static final Locale UKRAINIAN_LOCALE = Locale.forLanguageTag("uk-UA");

    private final Map<String, Locale> playerLocales = new ConcurrentHashMap<>();
    private final WIIC plugin;

    private TranslationStore.StringBased<MessageFormat> translationStore;

    public MessageManager(WIIC plugin) {
        this.plugin = plugin;
        setupTranslations();
    }

    private void setupTranslations() {
        try {
            // Create translation store
            translationStore = TranslationStore.messageFormat(Key.key("wiic", "translations"));

            // Load English translations
            try {
                ResourceBundle englishBundle = ResourceBundle.getBundle("lang/en_US", DEFAULT_LOCALE, 
                        plugin.getClass().getClassLoader(), UTF8ResourceBundleControl.get());
                translationStore.registerAll(DEFAULT_LOCALE, englishBundle, true);
                plugin.getLogger().info("Loaded English translations");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load English translations: " + e.getMessage());
            }

            // Load Ukrainian translations
            try {
                ResourceBundle ukrainianBundle = ResourceBundle.getBundle("lang/uk_UA", UKRAINIAN_LOCALE, 
                        plugin.getClass().getClassLoader(), UTF8ResourceBundleControl.get());
                translationStore.registerAll(UKRAINIAN_LOCALE, ukrainianBundle, true);
                plugin.getLogger().info("Loaded Ukrainian translations");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load Ukrainian translations: " + e.getMessage());
            }

            // Register with GlobalTranslator
            GlobalTranslator.translator().addSource(translationStore);
            plugin.getLogger().info("Translation system initialized");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to setup translations", e);
        }
    }


    /**
     * Get a translatable component for the given key
     * @param key Translation key
     * @param args Optional arguments for message formatting
     * @return TranslatableComponent that will be rendered in the player's locale
     */
    public Component getMessage(String key, Object... args) {
        if (args.length > 0) {
            return Component.translatable(key, Component.text(String.valueOf(args[0])),
                    args.length > 1 ? Component.text(String.valueOf(args[1])) : Component.empty(),
                    args.length > 2 ? Component.text(String.valueOf(args[2])) : Component.empty());
        }
        return Component.translatable(key);
    }

    /**
     * Get a translatable component rendered for a specific player's locale
     * @param player Player to get locale for
     * @param key Translation key
     * @param args Optional arguments for message formatting
     * @return Component rendered in player's locale
     */
    public Component getPlayerMessage(Player player, String key, Object... args) {
        Locale playerLocale = getPlayerLocale(player);
        Component component = getMessage(key, args);
        return GlobalTranslator.render(component, playerLocale);
    }

    /**
     * Send a translated message to a player
     * @param player Target player
     * @param key Translation key
     * @param args Optional arguments for message formatting
     */
    public void sendMessage(Player player, String key, Object... args) {
        player.sendMessage(getPlayerMessage(player, key, args));
    }

    /**
     * Get cached player locale or detect from client
     * @param player Player to get locale for
     * @return Player's locale or default
     */
    public Locale getPlayerLocale(Player player) {
        return playerLocales.computeIfAbsent(player.getUniqueId().toString(),
                k -> detectPlayerLocale(player));
    }

    /**
     * Cache player locale for better performance
     * @param player Player
     * @param locale Locale to cache
     */
    public void cachePlayerLocale(Player player, Locale locale) {
        playerLocales.put(player.getUniqueId().toString(), locale);
    }

    /**
     * Remove cached player locale
     * @param player Player
     */
    public void removeCachedLocale(Player player) {
        playerLocales.remove(player.getUniqueId().toString());
    }

    private Locale detectPlayerLocale(Player player) {
        try {
            Locale clientLocale = player.locale();

            // Check if we support Ukrainian
            if ("uk".equals(clientLocale.getLanguage())) {
                return UKRAINIAN_LOCALE;
            }
            
            // Check if we support this exact locale
            if (DEFAULT_LOCALE.equals(clientLocale) || UKRAINIAN_LOCALE.equals(clientLocale)) {
                return clientLocale;
            }

            // Fallback to default
            return DEFAULT_LOCALE;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to detect player locale for " + player.getName(), e);
            return DEFAULT_LOCALE;
        }
    }

    /**
     * Reload all translations
     */
    public void reloadTranslations() {
        playerLocales.clear();

        // Unregister old translation store
        if (translationStore != null) {
            GlobalTranslator.translator().removeSource(translationStore);
        }

        setupTranslations();
        plugin.getLogger().info("Translations reloaded");
    }

    /**
     * Get supported locales
     * @return Set of supported locales
     */
    public java.util.Set<Locale> getSupportedLocales() {
        return java.util.Set.of(DEFAULT_LOCALE, UKRAINIAN_LOCALE);
    }

    /**
     * Check if a translation key exists
     * @param key Translation key to check
     * @return true if key exists (basic check - assumes key exists if store is loaded)
     */
    public boolean hasTranslation(String key) {
        return translationStore != null;
    }
}