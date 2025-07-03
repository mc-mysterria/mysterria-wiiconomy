package dev.ua.ikeepcalm.wiic.utils.item;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

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

}
