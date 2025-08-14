package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.locale.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class CoinUtil {
    public static ItemStack getVerlDor(int amount) {
        ItemStack verlDor = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItemTranslatable(verlDor, "goldcoin", "wiic.items.coin.verldor", NamedTextColor.YELLOW, "verlDor");
        return verlDor;
    }

    public static ItemStack getVerlDor() { return getVerlDor(1); }

    public static ItemStack getLick(int amount) {
        ItemStack lick = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItemTranslatable(lick, "silvercoin", "wiic.items.coin.lick", NamedTextColor.GRAY, "lick");
        return lick;
    }

    public static ItemStack getLick() { return getLick(1); }

    public static ItemStack getCoppet(int amount) {
        ItemStack coppet = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItemTranslatable(coppet, "coppercoin", "wiic.items.coin.coppet", NamedTextColor.GOLD, "coppet");
        return coppet;
    }

    public static ItemStack getCoppet() { return getCoppet(1); }

    public static boolean isCoin(ItemStack item) {
        if (item == null || item.getType() != Material.GOLD_INGOT || !item.hasItemMeta()) {
            return false;
        }
        final String type = ItemUtil.getType(item);
        if (type == null) return false;
        return switch (type) {
            case "coppercoin", "silvercoin", "goldcoin" -> true;
            default -> false;
        };
    }

    public static String getFormattedPrice(int cost) {
        MessageManager messageManager = WIIC.INSTANCE.getMessageManager();
        
        int verlDors = cost / (64 * 64);
        cost %= 64 * 64;
        int licks = cost / 64;
        cost %= 64;
        
        StringBuilder result = new StringBuilder();
        
        if (cost > 0) {
            Component coppetText = messageManager.getMessage("wiic.currency.coppet_short");
            String coppetStr = LegacyComponentSerializer.legacySection().serialize(coppetText);
            result.append(cost).append(" ").append(coppetStr);
        }
        
        if (licks > 0) {
            Component lickText = messageManager.getMessage("wiic.currency.lick_short");
            String lickStr = LegacyComponentSerializer.legacySection().serialize(lickText);
            if (result.length() > 0) {
                result.insert(0, licks + " " + lickStr + " ");
            } else {
                result.append(licks).append(" ").append(lickStr);
            }
        }
        
        if (verlDors > 0) {
            Component verlDorText = messageManager.getMessage("wiic.currency.verldor_short");
            String verlDorStr = LegacyComponentSerializer.legacySection().serialize(verlDorText);
            if (result.length() > 0) {
                result.insert(0, verlDors + " " + verlDorStr + " ");
            } else {
                result.append(verlDors).append(" ").append(verlDorStr);
            }
        }
        
        if (result.length() == 0) {
            Component zeroText = messageManager.getMessage("wiic.currency.zero_coppets");
            return LegacyComponentSerializer.legacySection().serialize(zeroText);
        }
        
        return result.toString().trim();
    }
}
