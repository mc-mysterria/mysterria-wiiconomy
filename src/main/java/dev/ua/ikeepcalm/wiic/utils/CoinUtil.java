package dev.ua.ikeepcalm.wiic.utils;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class CoinUtil {
    public static ItemStack getVerlDor() {
        ItemStack verlDor = new ItemStack(Material.GOLD_INGOT);
        ItemUtil.modifyItem(verlDor, "verlDor", "Аур", NamedTextColor.YELLOW, 1);
        return verlDor;
    }

    public static ItemStack getLick() {
        ItemStack lick = new ItemStack(Material.GOLD_INGOT);
        ItemUtil.modifyItem(lick, "lick", "Лік", NamedTextColor.GRAY, 2);
        return lick;
    }

    public static ItemStack getCoppet() {
        ItemStack coppet = new ItemStack(Material.GOLD_INGOT);
        ItemUtil.modifyItem(coppet, "coppet", "Копійка", NamedTextColor.GOLD, 3);
        return coppet;
    }
}
