package dev.ua.ikeepcalm.wiic.economy;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.Configuration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class Appraiser {


    private static Configuration config;

    public Appraiser() {
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

        if (config.contains("minimumValue")) {
            value = Math.max(value, config.getDouble("minimumValue"));
        }

        return (int) value;
    }

    public List<Component> getDetailedAppraisal(ItemStack itemStack) {
        List<Component> details = new ArrayList<>();

        double basePrice = getItemCategory(itemStack.getType());
        if (basePrice == 0) {
            details.add(Component.text("Цей предмет не може бути оцінений").color(NamedTextColor.RED));
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

        double finalPrice = basePrice;
        details.add(Component.text("Базова ціна: " + basePrice).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        details.add(Component.text("Унікальність: " + materialCoefficient).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        details.add(Component.text("Кількість: " + itemStack.getAmount()).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        finalPrice *= materialCoefficient;

        double value = finalPrice * itemStack.getAmount();
        details.add(Component.text("Фінальна оцінка: " + value).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        return details;
    }

    private double getItemCategory(Material material) {
        if (Tag.COAL_ORES.isTagged(material)) {
            return config.getDouble("ores.COAL_ORE");
        }
        if (Tag.COPPER_ORES.isTagged(material)) {
            return config.getDouble("ores.COPPER_ORE");
        }
        if (Tag.IRON_ORES.isTagged(material)) {
            return config.getDouble("ores.IRON_ORE");
        }
        if (Tag.GOLD_ORES.isTagged(material)) {
            return config.getDouble("ores.GOLD_ORE");
        }
        if (Tag.LAPIS_ORES.isTagged(material)) {
            return config.getDouble("ores.LAPIS_ORE");
        }
        if (Tag.REDSTONE_ORES.isTagged(material)) {
            return config.getDouble("ores.REDSTONE_ORE");
        }
        if (Tag.DIAMOND_ORES.isTagged(material)) {
            return config.getDouble("ores.DIAMOND_ORE");
        }
        if (Tag.EMERALD_ORES.isTagged(material)) {
            return config.getDouble("ores.EMERALD_ORE");
        }
        if (material == Material.ANCIENT_DEBRIS) {
            return config.getDouble("ores.ANCIENT_DEBRIS");
        }

        if (material == Material.NETHER_QUARTZ_ORE) {
            return config.getDouble("ores.NETHER_QUARTZ_ORE");
        }

        return config.getDouble("categories.OTHER");
    }
}
