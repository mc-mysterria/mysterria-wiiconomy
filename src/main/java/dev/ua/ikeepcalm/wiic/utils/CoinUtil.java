package dev.ua.ikeepcalm.wiic.utils;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class CoinUtil {
    public static ItemStack getVerlDor(int amount) {
        ItemStack verlDor = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItem(verlDor, "goldcoin", "Аур", NamedTextColor.YELLOW, "verlDor");
        return verlDor;
    }

    public static ItemStack getVerlDor() { return getVerlDor(1); }

    public static ItemStack getLick(int amount) {
        ItemStack lick = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItem(lick, "silvercoin", "Лік", NamedTextColor.GRAY, "lick");
        return lick;
    }

    public static ItemStack getLick() { return getLick(1); }

    public static ItemStack getCoppet(int amount) {
        ItemStack coppet = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItem(coppet, "coppercoin", "Копійка", NamedTextColor.GOLD, "coppet");
        return coppet;
    }

    public static ItemStack getCoppet() { return getCoppet(1); }

    public static CoinType getCoinType(ItemStack item) {
        if (item == null || item.getType() != Material.GOLD_INGOT || !item.hasItemMeta()) {
            return CoinType.NONE;
        }
        final String type = ItemUtil.getType(item);
        if (type == null) return CoinType.NONE;
        return switch (type) {
            case "coppet", "lick", "verlDor" -> CoinType.OLD;
            case "coppercoin", "silvercoin", "goldcoin" -> CoinType.NEW;
            default -> CoinType.NONE;
        };
    }
}
