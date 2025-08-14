package dev.ua.ikeepcalm.wiic.currency.models;

import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
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
        ItemStack wallet = WalletUtil.getWallet();

        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, "wallet-item"), wallet).shape(
                "LLL",
                "DID",
                "LLL");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('D', Material.PAPER);
        recipe.setIngredient('I', Material.ECHO_SHARD);

        Bukkit.getServer().addRecipe(recipe);
    }

    public void createLickRecipe(JavaPlugin plugin) {
        ItemStack lick = CoinUtil.getLick(64);
        ItemStack verlDor = CoinUtil.getVerlDor();

        ShapelessRecipe recipe = new ShapelessRecipe(new NamespacedKey(plugin, "lick-item"), lick);
        recipe.addIngredient(new RecipeChoice.ExactChoice(verlDor));

        Bukkit.getServer().addRecipe(recipe);
    }

    public void createCoppetRecipe(JavaPlugin plugin) {
        ItemStack coppet = CoinUtil.getCoppet(64);
        ItemStack lick = CoinUtil.getLick();

        ShapelessRecipe recipe = new ShapelessRecipe(new NamespacedKey(plugin, "coppet-item"), coppet);
        recipe.addIngredient(new RecipeChoice.ExactChoice(lick));

        Bukkit.getServer().addRecipe(recipe);
    }

}
