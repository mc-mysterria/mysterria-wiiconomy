package dev.ua.ikeepcalm.wiic.listeners;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.List;

public class VillagerListener implements Listener {
    private final WIIC plugin;

    public VillagerListener(WIIC plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onVillagerAcquireTrade(VillagerAcquireTradeEvent event) {
        event.setRecipe(convertRecipe(event.getRecipe()));
    }

    private MerchantRecipe convertRecipe(MerchantRecipe recipe) {
        int emeralds = 0;
        List<ItemStack> ingredients = recipe.getIngredients();
        for (ItemStack item : new ArrayList<>(ingredients)) {
            if (item.getType() == Material.EMERALD) {
                emeralds += item.getAmount();
                ingredients.remove(item);
            }
        }

        int coppets = emeraldsToCoppets(emeralds);
        if (ingredients.isEmpty()) {
            int licks = coppets / 64;
            if (licks > 0) {
                ingredients.add(CoinUtil.getLick(licks));
            }

            coppets = coppets % 64;
            if (coppets > 0) {
                ingredients.add(CoinUtil.getCoppet(coppets));
            }
        } else if (ingredients.size() == 1 && coppets > 0) {
            if (coppets < 64) {
                ingredients.addFirst(CoinUtil.getCoppet(coppets));
            } else {
                ingredients.addFirst(CoinUtil.getLick((int) Math.round(coppets / 64.0)));
            }
        }

        ItemStack result = recipe.getResult();
        if (plugin.getConfig().getBoolean("villagers.convertTradeResults") && result.getType() == Material.EMERALD) {
            coppets = emeraldsToCoppets(result.getAmount());
            if (coppets >= 64) {
                result = CoinUtil.getLick((int) Math.round(coppets / 64.0));
            } else {
                result = CoinUtil.getCoppet(coppets);
            }
        }

        recipe = new MerchantRecipe(
            result,
            recipe.getUses(),
            recipe.getMaxUses(),
            recipe.hasExperienceReward(),
            recipe.getVillagerExperience(),
            recipe.getPriceMultiplier(),
            recipe.getDemand(),
            recipe.getSpecialPrice(),
            recipe.shouldIgnoreDiscounts()
        );
        recipe.setIngredients(ingredients);

        return recipe;
    }

    private int emeraldsToCoppets(int emeralds) {
        if (emeralds == 0) {
            return 0;
        }
        int coppets = (int) Math.round(emeralds * plugin.getConfig().getDouble("villagers.emeraldsToCoppetsMultiplier"));
        if (coppets == 0) {
            return 1;
        }
        return coppets;
    }
}
