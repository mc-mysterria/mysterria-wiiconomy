package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

public class CoinUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // -------------------------------------------------------------------------
    // Coin item constructors
    // -------------------------------------------------------------------------

    public static ItemStack getVerlDor(int amount) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT, amount);
        String name = WIIC.INSTANCE.getConfig().getString("coins.verldor.name", "<yellow>ᴠᴇʀʟ ᴅᴏʀ");
        applyCoinMeta(item, "goldcoin", name, "verlDor");
        return item;
    }

    public static ItemStack getVerlDor() {
        return getVerlDor(1);
    }

    public static ItemStack getLick(int amount) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT, amount);
        String name = WIIC.INSTANCE.getConfig().getString("coins.lick.name", "<gray>ʟɪᴄᴋ");
        applyCoinMeta(item, "silvercoin", name, "lick");
        return item;
    }

    public static ItemStack getLick() {
        return getLick(1);
    }

    public static ItemStack getCoppet(int amount) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT, amount);
        String name = WIIC.INSTANCE.getConfig().getString("coins.coppet.name", "<gold>ᴄᴏᴘᴘᴇᴛ");
        applyCoinMeta(item, "coppercoin", name, "coppet");
        return item;
    }

    public static ItemStack getCoppet() {
        return getCoppet(1);
    }

    // -------------------------------------------------------------------------
    // Coin detection
    // -------------------------------------------------------------------------

    public static boolean isCoin(ItemStack item) {
        if (item == null || item.getType() != Material.GOLD_INGOT || !item.hasItemMeta()) return false;
        String type = ItemUtil.getType(item);
        if (type == null) return false;
        return switch (type) {
            case "coppercoin", "silvercoin", "goldcoin" -> true;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Formatted price
    // -------------------------------------------------------------------------

    /**
     * Returns a MiniMessage-parsed {@link Component} representing {@code cost} coppets
     * broken down into VerlDors, Licks, and Coppets.
     * Format strings are read from {@code coins.*.format} in {@code config.yml}.
     */
    public static Component getFormattedPrice(int cost) {
        int verlDors = cost / (64 * 64);
        cost %= 64 * 64;
        int licks = cost / 64;
        cost %= 64;

        if (verlDors == 0 && licks == 0 && cost == 0) {
            String zero = WIIC.INSTANCE.getConfig().getString("coins.zero", "<gray>0 ᴄᴏᴘᴘᴇᴛs");
            return MM.deserialize(zero);
        }

        Component result = Component.empty();
        if (cost > 0) {
            String fmt = WIIC.INSTANCE.getConfig().getString("coins.coppet.format", "<gold>%value% ᴄᴏᴘᴘᴇᴛs");
            result = result.append(Component.space()).append(MM.deserialize(fmt.replace("%value%", String.valueOf(cost))));
        }
        if (licks > 0) {
            String fmt = WIIC.INSTANCE.getConfig().getString("coins.lick.format", "<gray>%value% ʟɪᴄᴋs");
            result = result.append(Component.space()).append(MM.deserialize(fmt.replace("%value%", String.valueOf(licks))));
        }
        if (verlDors > 0) {
            String fmt = WIIC.INSTANCE.getConfig().getString("coins.verldor.format", "<yellow>%value% ᴠᴇʀʟ ᴅᴏʀs");
            result = result.append(Component.space()).append(MM.deserialize(fmt.replace("%value%", String.valueOf(verlDors))));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Sets the PDC type tag, MiniMessage display name (non-italic), and item model
     * on the given coin item.
     */
    private static void applyCoinMeta(ItemStack item, String type, String nameStr, String model) {
        ItemUtil.setType(item, type);
        item.editMeta(meta -> {
            meta.displayName(MM.deserialize(nameStr).decoration(TextDecoration.ITALIC, false));
            meta.setItemModel(new NamespacedKey(WIIC.INSTANCE, model));
        });
    }
}
