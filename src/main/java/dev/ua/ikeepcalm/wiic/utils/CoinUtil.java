package dev.ua.ikeepcalm.wiic.utils;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class CoinUtil {
    public static ItemStack getVerlDor(int amount) {
        ItemStack verlDor = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItem(verlDor, "verlDor", "Аур", NamedTextColor.YELLOW, 1);
        return verlDor;
    }

    public static ItemStack getVerlDor() { return getVerlDor(1); }

    public static ItemStack getLick(int amount) {
        ItemStack lick = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItem(lick, "lick", "Лік", NamedTextColor.GRAY, 2);
        return lick;
    }

    public static ItemStack getLick() { return getLick(1); }

    public static ItemStack getCoppet(int amount) {
        ItemStack coppet = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItem(coppet, "coppet", "Копійка", NamedTextColor.GOLD, 3);
        return coppet;
    }

    public static ItemStack getCoppet() { return getCoppet(1); }
}
