package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class ItemUtil {
    private static NamespacedKey getTypeKey() {
        return new NamespacedKey(WIIC.INSTANCE, "type");
    }

    public static void setType(ItemStack item, String type) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(getTypeKey(), PersistentDataType.STRING, type);
        item.setItemMeta(meta);
    }

    public static @Nullable String getType(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(getTypeKey(), PersistentDataType.STRING);
    }

    public static void modifyItem(ItemStack item, String type, String displayName, NamedTextColor color, String model) {
        if (item == null) return;
        if (item.getAmount() > 0) {
            setType(item, type);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(displayName).color(color).decoration(TextDecoration.ITALIC, false));
            meta.setItemModel(new NamespacedKey(WIIC.INSTANCE, model));
            item.setItemMeta(meta);
        }
    }

    public static void modifyItemTranslatable(ItemStack item, String type, String translationKey, NamedTextColor color, String model) {
        if (item == null) return;
        if (item.getAmount() > 0) {
            setType(item, type);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.translatable(translationKey).color(color).decoration(TextDecoration.ITALIC, false));
            meta.setItemModel(new NamespacedKey(WIIC.INSTANCE, model));
            item.setItemMeta(meta);
        }
    }

    /**
     * Adds each item to the player's inventory, dropping anything that doesn't fit
     * at their feet so the given amount and the received-or-dropped amount always match.
     */
    public static void giveOrDrop(Player player, ItemStack... items) {
        Map<Integer, ItemStack> notGiven = player.getInventory().addItem(items);
        for (ItemStack leftover : notGiven.values()) {
            player.getWorld().dropItem(player.getLocation(), leftover);
        }
    }

}
