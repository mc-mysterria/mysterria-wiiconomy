package dev.ua.ikeepcalm.wiic.wallet.objects;

import de.tr7zw.nbtapi.NBT;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

public class WalletRecipe {

    public WalletRecipe(JavaPlugin plugin) {
        createWalletRecipe(plugin);
        createLickRecipe(plugin);
        createCoppetRecipe(plugin);
    }

    public void createWalletRecipe(JavaPlugin plugin) {
        ItemStack wallet = new ItemStack(Material.GLOWSTONE_DUST, 1);
        NBT.modify(wallet, nbt -> {
            nbt.setString("type", "wallet");
            nbt.modifyMeta((readableNBT, meta) -> {
                meta.setDisplayName(ChatColor.RESET + "" + ChatColor.AQUA + "Гаманець");
                meta.setCustomModelData(1488);
            });
        });

        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, "wallet-item"), wallet).shape(
                "LLL",
                "DID",
                "LLL");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('D', Material.PAPER);
        recipe.setIngredient('I', Material.WITHER_ROSE);

        Bukkit.getServer().addRecipe(recipe);
    }

    public void createLickRecipe(JavaPlugin plugin) {
        ItemStack lick = new ItemStack(Material.GOLD_INGOT, 64);
        ItemUtil.modifyItem(lick, "lick", "Лік", NamedTextColor.GRAY, 2);

        ItemStack verlDor = new ItemStack(Material.GOLD_INGOT, 1);
        ItemUtil.modifyItem(verlDor, "verlDor", "Аур", NamedTextColor.YELLOW, 1);

        ShapelessRecipe recipe = new ShapelessRecipe(new NamespacedKey(plugin, "lick-item"), lick);
        recipe.addIngredient(new RecipeChoice.ExactChoice(verlDor));

        Bukkit.getServer().addRecipe(recipe);
    }

    public void createCoppetRecipe(JavaPlugin plugin) {
        ItemStack coppet = new ItemStack(Material.GOLD_INGOT, 64);
        ItemUtil.modifyItem(coppet, "coppet", "Копійка", NamedTextColor.GOLD, 3);

        ItemStack lick = new ItemStack(Material.GOLD_INGOT, 64);
        ItemUtil.modifyItem(lick, "lick", "Лік", NamedTextColor.GRAY, 2);

        ShapelessRecipe recipe = new ShapelessRecipe(new NamespacedKey(plugin, "coppet-item"), coppet);
        recipe.addIngredient(new RecipeChoice.ExactChoice(lick));

        Bukkit.getServer().addRecipe(recipe);
    }

}
