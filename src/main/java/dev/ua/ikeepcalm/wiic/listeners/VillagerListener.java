package dev.ua.ikeepcalm.wiic.listeners;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.CoinType;
import dev.ua.ikeepcalm.wiic.currency.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.item.ItemUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VillagerListener implements Listener {
    private final WIIC plugin;
    private final NamespacedKey originalCostsKey;
    private final NamespacedKey originalResultsKey;
    private final NamespacedKey villagerVersionKey;

    public VillagerListener(WIIC plugin) {
        this.plugin = plugin;
        originalCostsKey = new NamespacedKey(plugin, "original_costs");
        originalResultsKey = new NamespacedKey(plugin, "original_results");
        villagerVersionKey = new NamespacedKey(plugin, "version");
    }

    @EventHandler
    public void onVillagerAcquireTrade(VillagerAcquireTradeEvent event) {
        saveOriginalCosts(event.getEntity(), event.getRecipe());
        event.setRecipe(convertRecipe(event.getRecipe()));
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof AbstractVillager villager) {
            int currentVersion = plugin.getConfig().getInt("villagers.version");
            PersistentDataContainer pdc = villager.getPersistentDataContainer();
            if (!Integer.valueOf(currentVersion).equals(
                pdc.get(villagerVersionKey, PersistentDataType.INTEGER)
            )) {
                int[] originalCosts = pdc.get(originalCostsKey, PersistentDataType.INTEGER_ARRAY);
                int[] originalResults = pdc.get(originalResultsKey, PersistentDataType.INTEGER_ARRAY);
                if (originalCosts == null || originalResults == null) {
                    originalCosts = new int[0];
                    originalResults = new int[0];
                }

                if (villager.getRecipeCount() > originalCosts.length) {
                    for (int i = originalCosts.length; i < villager.getRecipeCount(); i++) {
                        saveOriginalCosts(villager, villager.getRecipe(i), true);
                    }
                    originalCosts = pdc.get(originalCostsKey, PersistentDataType.INTEGER_ARRAY);
                    originalResults = pdc.get(originalResultsKey, PersistentDataType.INTEGER_ARRAY);
                }

                for (int i = 0; i < villager.getRecipeCount(); i++) {
                    MerchantRecipe recipe = villager.getRecipe(i);
                    List<ItemStack> ingredients = recipe.getIngredients();
                    ingredients.removeIf(ingredient -> ingredient.getType() == Material.EMERALD || CoinUtil.getCoinType(ingredient) != CoinType.NONE);
                    int emeralds = originalCosts[i];
                    if (emeralds == 0 && ingredients.size() == 1 && ingredients.getFirst().getType().equals(Material.BOOK) && ingredients.getFirst().getAmount() == 1) {
                        emeralds = 64;
                    }
                    if (emeralds > 64) {
                        emeralds -= 64;
                        ingredients.add(new ItemStack(Material.EMERALD, 64));
                    }
                    if (emeralds > 0) {
                        ingredients.add(new ItemStack(Material.EMERALD, emeralds));
                    }
                    ItemStack result = recipe.getResult();
                    if ((result.getType() == Material.EMERALD || CoinUtil.getCoinType(result) != CoinType.NONE) && originalResults[i] > 0) {
                        result = new ItemStack(Material.EMERALD, originalResults[i]);
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

                    villager.setRecipe(i, convertRecipe(recipe));
                }

                pdc.set(villagerVersionKey, PersistentDataType.INTEGER, currentVersion);
            }
        }
    }

    private void saveOriginalCosts(AbstractVillager villager, MerchantRecipe recipe) {
        saveOriginalCosts(villager, recipe, false);
    }

    private void saveOriginalCosts(AbstractVillager villager, MerchantRecipe recipe, boolean ignoreCurrentCount) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();

        if (!pdc.has(villagerVersionKey)) {
            pdc.set(villagerVersionKey, PersistentDataType.INTEGER, plugin.getConfig().getInt("villagers.version"));
        }

        int[] costsArray = pdc.get(originalCostsKey, PersistentDataType.INTEGER_ARRAY);
        int[] resultsArray = pdc.get(originalResultsKey, PersistentDataType.INTEGER_ARRAY);

        if (costsArray != null && resultsArray != null && (villager.getRecipeCount() == costsArray.length || ignoreCurrentCount)) {
            costsArray = Arrays.copyOf(costsArray, costsArray.length + 1);
            resultsArray = Arrays.copyOf(resultsArray, resultsArray.length + 1);
        } else {
            costsArray = new int[1];
            resultsArray = new int[1];
        }

        for (ItemStack item : recipe.getIngredients()) {
            costsArray[costsArray.length - 1] += convertToEmeralds(item);
        }
        resultsArray[resultsArray.length - 1] = convertToEmeralds(recipe.getResult());

        pdc.set(originalCostsKey, PersistentDataType.INTEGER_ARRAY, costsArray);
        pdc.set(originalResultsKey, PersistentDataType.INTEGER_ARRAY, resultsArray);
    }

    private MerchantRecipe convertRecipe(MerchantRecipe recipe) {
        ItemStack result = recipe.getResult();
        List<ItemStack> ingredients = recipe.getIngredients();

        if (!plugin.getConfig().getBoolean("villagers.useCoinsOnlyForRareItems") || isRare(result.getType())) {
            int emeralds = 0;
            for (ItemStack item : new ArrayList<>(ingredients)) {
                if (item.getType() == Material.EMERALD) {
                    emeralds += item.getAmount();
                    ingredients.remove(item);
                }
            }

//            if (emeralds > 0) {
//                float priceMultiplier = recipe.getPriceMultiplier();
//                int demand = recipe.getDemand();
//                int specialPrice = recipe.getSpecialPrice();
//
//                emeralds = Math.max(1, Math.round(emeralds * priceMultiplier + demand + specialPrice));
//            }

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
        }

        if (plugin.getConfig().getBoolean("villagers.convertTradeResults") && result.getType() == Material.EMERALD) {
            int coppets = emeraldsToCoppets(result.getAmount());
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

    private int convertToEmeralds(ItemStack item) {
        if (item.getType() == Material.EMERALD) {
            return item.getAmount();
        }
        final String coinType = ItemUtil.getType(item);
        if ("coppet".equals(coinType) || "coppercoin".equals(coinType)) {
            return (int) Math.round(item.getAmount() / plugin.getConfig().getDouble("villagers.emeraldsToCoppetsMultiplier"));
        }
        if ("lick".equals(coinType) || "silvercoin".equals(coinType)) {
            return (int) Math.round(item.getAmount() * 64 / plugin.getConfig().getDouble("villagers.emeraldsToCoppetsMultiplier"));
        }
        return 0;
    }

    private boolean isRare(Material material) {
        return material == Material.ENCHANTED_BOOK || material.getMaxDurability() != 0;
    }
}
