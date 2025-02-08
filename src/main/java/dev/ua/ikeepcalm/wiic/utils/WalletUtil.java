package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class WalletUtil {
    public static ItemStack getWallet() {
        ItemStack wallet = new ItemStack(Material.GLOWSTONE_DUST, 1);
        ItemMeta meta = wallet.getItemMeta();
        meta.setMaxStackSize(1);
        wallet.setItemMeta(meta);
        ItemUtil.modifyItem(wallet, "wallet", "Гаманець", NamedTextColor.AQUA);
        return wallet;
    }

    public static boolean hasWalletData(ItemStack wallet) {
        ItemMeta meta = wallet.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(getWalletIdKey(), PersistentDataType.STRING) && pdc.has(getWalletOwnerKey(), PersistentDataType.STRING);
    }

    private static NamespacedKey getWalletIdKey() {
        return new NamespacedKey(WIIC.INSTANCE, "id");
    }

    private static NamespacedKey getWalletOwnerKey() {
        return new NamespacedKey(WIIC.INSTANCE, "owner");
    }

    public static void setWalletData(ItemStack wallet, UUID id, String owner) {
        ItemMeta meta = wallet.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(getWalletIdKey(), PersistentDataType.STRING, id.toString());
        pdc.set(getWalletOwnerKey(), PersistentDataType.STRING, owner);
        wallet.setItemMeta(meta);
    }

    public static String getWalletId(ItemStack wallet) {
        return wallet.getItemMeta().getPersistentDataContainer().get(getWalletIdKey(), PersistentDataType.STRING);
    }

    public static String getWalletOwner(ItemStack wallet) {
        return wallet.getItemMeta().getPersistentDataContainer().get(getWalletOwnerKey(), PersistentDataType.STRING);
    }

    public static boolean isWallet(ItemStack item) {
        if (item == null || item.getType() != Material.GLOWSTONE_DUST || !item.hasItemMeta()) {
            return false;
        }
        return "wallet".equals(ItemUtil.getType(item));
    }
}
