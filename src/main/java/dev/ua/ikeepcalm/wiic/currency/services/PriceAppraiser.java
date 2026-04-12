package dev.ua.ikeepcalm.wiic.currency.services;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.Configuration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class PriceAppraiser {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static Configuration config;

    public PriceAppraiser() {
        loadConfig();
    }

    public static void loadConfig() {
        config = WIIC.INSTANCE.getConfig();
        config.options().copyDefaults(true);
    }

    public int appraise(ItemStack itemStack) {
        double basePrice = getItemCategory(itemStack.getType());

        double materialCoefficient = 1.0;
        if (itemStack.hasItemMeta() && itemStack.getItemMeta() != null && itemStack.getItemMeta().hasRarity()) {
            materialCoefficient = switch (itemStack.getItemMeta().getRarity()) {
                case COMMON -> config.getDouble("rarities.COMMON");
                case UNCOMMON -> config.getDouble("rarities.UNCOMMON");
                case RARE -> config.getDouble("rarities.RARE");
                case EPIC -> config.getDouble("rarities.EPIC");
            };
        }

        double value = basePrice * materialCoefficient * itemStack.getAmount();

        if (config.contains("minimum-value")) {
            value = Math.max(value, config.getDouble("minimum-value"));
        }

        return (int) value;
    }

    /**
     * Returns MiniMessage-formatted appraisal detail lines for the given item.
     * All strings are read from {@code appraiser.*} in {@code config.yml}; hardcoded
     * defaults are used as fallback. Each line has italic explicitly disabled so it
     * renders correctly in item lore.
     */
    public List<Component> getDetailedAppraisal(ItemStack itemStack, int availableAmount) {
        List<Component> details = new ArrayList<>();

        double basePrice = getItemCategory(itemStack.getType());
        if (basePrice == 0) {
            details.add(line("appraiser.cannot-appraise", "<red>ᴄᴀɴɴᴏᴛ ᴀᴘᴘʀᴀɪsᴇ", null));
            return details;
        }

        double materialCoefficient = 1.0;
        if (itemStack.hasItemMeta() && itemStack.getItemMeta() != null && itemStack.getItemMeta().hasRarity()) {
            materialCoefficient = switch (itemStack.getItemMeta().getRarity()) {
                case COMMON -> config.getDouble("rarities.COMMON");
                case UNCOMMON -> config.getDouble("rarities.UNCOMMON");
                case RARE -> config.getDouble("rarities.RARE");
                case EPIC -> config.getDouble("rarities.EPIC");
            };
        }

        details.add(line("appraiser.base-price",    "<dark_gray>ʙᴀsᴇ ᴘʀɪᴄᴇ: <gray>%value%",    num(basePrice)));
        details.add(line("appraiser.uniqueness",     "<dark_gray>ᴜɴɪQᴜᴇɴᴇss: <gray>%value%",     num(materialCoefficient)));
        details.add(line("appraiser.quantity",       "<dark_gray>Qᴜᴀɴᴛɪᴛʏ: <gray>%value%",       String.valueOf(itemStack.getAmount())));

        double value = basePrice * materialCoefficient * itemStack.getAmount();
        details.add(line("appraiser.final-appraisal", "<#FFD700>ꜰɪɴᴀʟ: <white>%value%",         num(value)));

        if ((int) value > availableAmount) {
            details.add(line("appraiser.cannot-sell",  "<red>ᴄᴀɴɴᴏᴛ sᴇʟʟ",                     null));
            details.add(line("appraiser.daily-limit",  "<red>ᴅᴀɪʟʏ ʟɪᴍɪᴛ: <gray>%value%",      String.valueOf(availableAmount)));
        }

        return details;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Reads a MiniMessage string from config (falling back to {@code def}), substitutes %value%, and parses it. */
    private static Component line(String key, String def, @Nullable String value) {
        String text = config.getString(key, def);
        if (value != null) text = text.replace("%value%", value);
        return MM.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    /** Formats a double without trailing ".0" when it is a whole number. */
    private static String num(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private double getItemCategory(Material material) {
        if (Tag.COAL_ORES.isTagged(material)) return config.getDouble("ores.COAL_ORE");
        if (Tag.COPPER_ORES.isTagged(material)) return config.getDouble("ores.COPPER_ORE");
        if (Tag.IRON_ORES.isTagged(material)) return config.getDouble("ores.IRON_ORE");
        if (Tag.GOLD_ORES.isTagged(material)) return config.getDouble("ores.GOLD_ORE");
        if (Tag.LAPIS_ORES.isTagged(material)) return config.getDouble("ores.LAPIS_ORE");
        if (Tag.REDSTONE_ORES.isTagged(material)) return config.getDouble("ores.REDSTONE_ORE");
        if (Tag.DIAMOND_ORES.isTagged(material)) return config.getDouble("ores.DIAMOND_ORE");
        if (Tag.EMERALD_ORES.isTagged(material)) return config.getDouble("ores.EMERALD_ORE");
        if (material == Material.ANCIENT_DEBRIS) return config.getDouble("ores.ANCIENT_DEBRIS");
        if (material == Material.NETHER_QUARTZ_ORE) return config.getDouble("ores.NETHER_QUARTZ_ORE");
        return config.getDouble("ores.OTHER");
    }
}
