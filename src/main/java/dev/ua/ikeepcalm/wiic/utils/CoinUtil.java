package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.locale.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class CoinUtil {
    public static ItemStack getVerlDor(int amount) {
        ItemStack verlDor = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItemTranslatable(verlDor, "goldcoin", "item.wiic.goldcoin", NamedTextColor.YELLOW, "verlDor");
        return verlDor;
    }

    public static ItemStack getVerlDor() {
        return getVerlDor(1);
    }

    public static ItemStack getLick(int amount) {
        ItemStack lick = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItemTranslatable(lick, "silvercoin", "item.wiic.silvercoin", NamedTextColor.GRAY, "lick");
        return lick;
    }

    public static ItemStack getLick() {
        return getLick(1);
    }

    public static ItemStack getCoppet(int amount) {
        ItemStack coppet = new ItemStack(Material.GOLD_INGOT, amount);
        ItemUtil.modifyItemTranslatable(coppet, "coppercoin", "item.wiic.coppercoin", NamedTextColor.GOLD, "coppet");
        return coppet;
    }

    public static ItemStack getCoppet() {
        return getCoppet(1);
    }

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

    public static Component getFormattedPrice(int cost) {
        int verlDors = cost / (64 * 64);
        cost %= 64 * 64;
        int licks = cost / 64;
        cost %= 64;

        if (cost == 0 && licks == 0 && verlDors == 0) {
            return MessageManager.getMessage("wiic.currency.zero_coppets");

        }

        Component finalResult = Component.text("");

        if (cost > 0) {
            finalResult = finalResult.append(Component.text(" ").append(MessageManager.getMessage("wiic.items.coin.coppets", String.valueOf(cost))));
        }

        if (licks > 0) {
            Component lickText = MessageManager.getMessage("wiic.items.coin.licks", String.valueOf(licks));
            finalResult = finalResult.append(Component.text(" ").append(lickText));
        }

        if (verlDors > 0) {
            Component verlDorText = MessageManager.getMessage("wiic.items.coin.verldors", String.valueOf(verlDors));
            finalResult = finalResult.append(Component.text(" ").append(verlDorText));
        }

        return finalResult;
    }
}
