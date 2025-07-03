package dev.ua.ikeepcalm.wiic.utils.item;

import dev.ua.ikeepcalm.wiic.utils.common.Translator;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LegacyItemUtil {
    public static ItemStack createStack(Material material, boolean glow, String... strings) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (strings.length >= 1) {
            if (!strings[0].isEmpty()) {
                meta.setDisplayName(ChatColor.RESET + Translator.translate(strings[0]));
            }
        } else {
            meta.setDisplayName(" ");
        }
        if (strings.length >= 2) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : meta.getLore();
            for (int i = 1; i < strings.length; ++i) {
                lore.add(Translator.translate(strings[i]));
            }
            meta.setLore(lore);
        }
        itemStack.setItemMeta(meta);
        if (glow) {
            LegacyItemUtil.addGlow(itemStack);
        }
        return itemStack;
    }

    public static ItemStack addGlow(ItemStack itemStack) {
        itemStack.addUnsafeEnchantment(Enchantment.CHANNELING, 1);
        ItemMeta meta = itemStack.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    public static ItemStack createStack(ItemStack itemStack, String... strings) {
        ItemStack stack = itemStack.clone();
        ItemMeta meta = stack.getItemMeta();
        if (strings.length >= 1) {
            if (!strings[0].isEmpty()) {
                meta.setDisplayName(ChatColor.RESET + Translator.translate(strings[0]));
            }
        } else {
            meta.setDisplayName(" ");
        }
        if (strings.length >= 2) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : meta.getLore();
            for (int i = 1; i < strings.length; ++i) {
                lore.add(Translator.translate(strings[i]));
            }
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> notGiven = player.getInventory().addItem(item);
        for (ItemStack itemToDrop : notGiven.values()) {
            player.getWorld().dropItem(player.getLocation(), itemToDrop);
        }
    }

    private LegacyItemUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
