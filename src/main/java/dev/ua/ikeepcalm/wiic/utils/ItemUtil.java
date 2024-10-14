package dev.ua.ikeepcalm.wiic.utils;

import de.tr7zw.nbtapi.NBT;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;

public class ItemUtil {

    public static void modifyItem(ItemStack item, String type, String displayName, NamedTextColor color, int customModelData) {
        if (item == null) return;
        if (item.getAmount() > 0) {
            NBT.modify(item, nbtItem -> {
                nbtItem.setString("type", type);
                nbtItem.modifyMeta((readableNBT, meta) -> {
                    meta.displayName(Component.text(displayName).color(color).decoration(TextDecoration.ITALIC, false));
                    meta.setCustomModelData(customModelData);
                });
            });
        }
    }

}
