package dev.ua.ikeepcalm.wiic.commands.debug;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.CoinType;
import dev.ua.ikeepcalm.wiic.currency.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.item.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;

public class VillagerDebugCommand implements CommandExecutor {

    private final WIIC plugin;
    private final NamespacedKey originalCostsKey;
    private final NamespacedKey originalResultsKey;
    private final NamespacedKey villagerVersionKey;

    public VillagerDebugCommand(WIIC plugin) {
        this.plugin = plugin;
        this.originalCostsKey = new NamespacedKey(plugin, "original_costs");
        this.originalResultsKey = new NamespacedKey(plugin, "original_results");
        this.villagerVersionKey = new NamespacedKey(plugin, "version");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be executed by a player!").color(NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission("wiic.debug.villager")) {
            player.sendMessage(Component.text("You don't have permission to use this command!").color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "scan":
                scanNearbyVillagers(player);
                break;
            case "info":
                showVillagerInfo(player);
                break;
            case "discounts":
                showDiscountInfo(player);
                break;
            case "recipes":
                showRecipeInfo(player);
                break;
            case "config":
                showConfigInfo(player);
                break;
            default:
                sendUsage(player);
        }

        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(Component.text("=== Villager Debug Commands ===").color(NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/villager-debug scan").color(NamedTextColor.GOLD)
                .append(Component.text(" - Scan nearby villagers").color(NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/villager-debug info").color(NamedTextColor.GOLD)
                .append(Component.text(" - Show detailed villager info (look at villager)").color(NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/villager-debug discounts").color(NamedTextColor.GOLD)
                .append(Component.text(" - Show discount analysis (look at villager)").color(NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/villager-debug recipes").color(NamedTextColor.GOLD)
                .append(Component.text(" - Show recipe conversion details (look at villager)").color(NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/villager-debug config").color(NamedTextColor.GOLD)
                .append(Component.text(" - Show plugin configuration").color(NamedTextColor.WHITE)));
    }

    private void scanNearbyVillagers(Player player) {
        List<AbstractVillager> nearbyVillagers = player.getNearbyEntities(10, 10, 10).stream()
                .filter(entity -> entity instanceof AbstractVillager)
                .map(entity -> (AbstractVillager) entity)
                .toList();

        player.sendMessage(Component.text("=== Nearby Villagers Scan ===").color(NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Found ").color(NamedTextColor.WHITE)
                .append(Component.text(nearbyVillagers.size()).color(NamedTextColor.GREEN))
                .append(Component.text(" villagers within 10 blocks").color(NamedTextColor.WHITE)));

        for (int i = 0; i < nearbyVillagers.size(); i++) {
            AbstractVillager villager = nearbyVillagers.get(i);
            String type = villager instanceof Villager ? "Villager" : "Wandering Trader";
            String profession = villager instanceof Villager v ? v.getProfession().toString() : "TRADER";
            int level = villager instanceof Villager v ? v.getVillagerLevel() : 1;
            
            player.sendMessage(Component.text((i + 1) + ". ").color(NamedTextColor.GRAY)
                    .append(Component.text(type).color(NamedTextColor.AQUA))
                    .append(Component.text(" (" + profession + " L" + level + ") - " + villager.getRecipeCount() + " recipes").color(NamedTextColor.WHITE)));
        }
    }

    private void showVillagerInfo(Player player) {
        AbstractVillager villager = getTargetVillager(player);
        if (villager == null) return;

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        
        player.sendMessage(Component.text("=== Villager Info ===").color(NamedTextColor.YELLOW));
        
        if (villager instanceof Villager v) {
            player.sendMessage(Component.text("Type: ").color(NamedTextColor.WHITE)
                    .append(Component.text("Villager").color(NamedTextColor.AQUA)));
            player.sendMessage(Component.text("Profession: ").color(NamedTextColor.WHITE)
                    .append(Component.text(v.getProfession().toString()).color(NamedTextColor.GREEN)));
            player.sendMessage(Component.text("Level: ").color(NamedTextColor.WHITE)
                    .append(Component.text(v.getVillagerLevel()).color(NamedTextColor.GOLD)));
            player.sendMessage(Component.text("Experience: ").color(NamedTextColor.WHITE)
                    .append(Component.text(v.getVillagerExperience()).color(NamedTextColor.YELLOW)));
            
            Map<java.util.UUID, Reputation> reputationMap = v.getReputations();
            Reputation playerReputation = reputationMap.get(player.getUniqueId());
            if (playerReputation != null) {
                int totalReputation = playerReputation.getReputation(ReputationType.MAJOR_POSITIVE) +
                                    playerReputation.getReputation(ReputationType.MINOR_POSITIVE) -
                                    playerReputation.getReputation(ReputationType.MAJOR_NEGATIVE) -
                                    playerReputation.getReputation(ReputationType.MINOR_NEGATIVE);
                player.sendMessage(Component.text("Reputation: ").color(NamedTextColor.WHITE)
                        .append(Component.text(totalReputation).color(TextColor.color(0xDDA0DD))));
            } else {
                player.sendMessage(Component.text("Reputation: ").color(NamedTextColor.WHITE)
                        .append(Component.text("No reputation data").color(NamedTextColor.GRAY)));
            }
        } else {
            player.sendMessage(Component.text("Type: ").color(NamedTextColor.WHITE)
                    .append(Component.text("Wandering Trader").color(NamedTextColor.AQUA)));
        }

        player.sendMessage(Component.text("Recipe Count: ").color(NamedTextColor.WHITE)
                .append(Component.text(villager.getRecipeCount()).color(NamedTextColor.GREEN)));
        
        // Plugin-specific data
        int currentVersion = plugin.getConfig().getInt("villagers.version");
        Integer storedVersion = pdc.get(villagerVersionKey, PersistentDataType.INTEGER);
        player.sendMessage(Component.text("Plugin Version: ").color(NamedTextColor.WHITE)
                .append(Component.text(currentVersion).color(NamedTextColor.YELLOW))
                .append(Component.text(" (Stored: " + (storedVersion != null ? storedVersion : "None") + ")").color(NamedTextColor.WHITE)));
        
        int[] originalCosts = pdc.get(originalCostsKey, PersistentDataType.INTEGER_ARRAY);
        int[] originalResults = pdc.get(originalResultsKey, PersistentDataType.INTEGER_ARRAY);
        player.sendMessage(Component.text("Original Costs Stored: ").color(NamedTextColor.WHITE)
                .append(Component.text((originalCosts != null ? originalCosts.length : 0) + " recipes").color(NamedTextColor.AQUA)));
        player.sendMessage(Component.text("Original Results Stored: ").color(NamedTextColor.WHITE)
                .append(Component.text((originalResults != null ? originalResults.length : 0) + " recipes").color(NamedTextColor.AQUA)));
    }

    private void showDiscountInfo(Player player) {
        AbstractVillager villager = getTargetVillager(player);
        if (villager == null) return;

        player.sendMessage(Component.text("=== Discount Analysis ===").color(NamedTextColor.YELLOW));
        
        // Check Hero of the Village effect
        PotionEffect heroEffect = player.getPotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        if (heroEffect != null) {
            player.sendMessage(Component.text("✓ Hero of the Village: Level " + (heroEffect.getAmplifier() + 1) + 
                             " (Duration: " + heroEffect.getDuration() / 20 + "s)").color(NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("✗ No Hero of the Village effect").color(NamedTextColor.RED));
        }

        // Check player reputation with villager
        if (villager instanceof Villager v) {
            Map<java.util.UUID, Reputation> reputationMap = v.getReputations();
            Reputation playerReputation = reputationMap.get(player.getUniqueId());
            
            if (playerReputation != null) {
                int majorPositive = playerReputation.getReputation(ReputationType.MAJOR_POSITIVE);
                int minorPositive = playerReputation.getReputation(ReputationType.MINOR_POSITIVE);
                int majorNegative = playerReputation.getReputation(ReputationType.MAJOR_NEGATIVE);
                int minorNegative = playerReputation.getReputation(ReputationType.MINOR_NEGATIVE);
                int totalReputation = majorPositive + minorPositive - majorNegative - minorNegative;
                
                player.sendMessage(Component.text("Reputation with villager: ").color(NamedTextColor.WHITE)
                        .append(Component.text(totalReputation).color(NamedTextColor.YELLOW)));
                
                // Show individual reputation types
                if (majorPositive > 0) {
                    player.sendMessage(Component.text("  Major Positive: " + majorPositive).color(NamedTextColor.GREEN));
                }
                if (minorPositive > 0) {
                    player.sendMessage(Component.text("  Minor Positive: " + minorPositive).color(NamedTextColor.GREEN));
                }
                if (majorNegative > 0) {
                    player.sendMessage(Component.text("  Major Negative: " + majorNegative).color(NamedTextColor.RED));
                }
                if (minorNegative > 0) {
                    player.sendMessage(Component.text("  Minor Negative: " + minorNegative).color(NamedTextColor.RED));
                }
                
                if (totalReputation > 0) {
                    player.sendMessage(Component.text("✓ Positive reputation (may provide discounts)").color(NamedTextColor.GREEN));
                } else if (totalReputation < 0) {
                    player.sendMessage(Component.text("✗ Negative reputation (may increase prices)").color(NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text("~ Neutral reputation").color(NamedTextColor.GRAY));
                }
            } else {
                player.sendMessage(Component.text("No reputation data with this villager").color(NamedTextColor.GRAY));
            }
        }

        // Analyze each recipe for discount application
        player.sendMessage(Component.text("Recipe Discount Analysis:").color(NamedTextColor.YELLOW));
        
        for (int i = 0; i < villager.getRecipeCount(); i++) {
            MerchantRecipe recipe = villager.getRecipe(i);
            player.sendMessage(Component.text("Recipe " + (i + 1) + ":").color(NamedTextColor.GRAY));
            player.sendMessage(Component.text("  Ignore Discounts: ").color(NamedTextColor.WHITE)
                    .append(Component.text(recipe.shouldIgnoreDiscounts() ? "YES" : "NO")
                            .color(recipe.shouldIgnoreDiscounts() ? NamedTextColor.RED : NamedTextColor.GREEN)));
            player.sendMessage(Component.text("  Price Multiplier: ").color(NamedTextColor.WHITE)
                    .append(Component.text(recipe.getPriceMultiplier()).color(NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("  Demand: ").color(NamedTextColor.WHITE)
                    .append(Component.text(recipe.getDemand()).color(NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("  Special Price: ").color(NamedTextColor.WHITE)
                    .append(Component.text(recipe.getSpecialPrice()).color(NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("  Uses: ").color(NamedTextColor.WHITE)
                    .append(Component.text(recipe.getUses() + "/" + recipe.getMaxUses()).color(NamedTextColor.AQUA)));
            
            // Show why discounts might not be applied
            if (recipe.shouldIgnoreDiscounts()) {
                player.sendMessage(Component.text("  → Discounts are disabled for this recipe!").color(NamedTextColor.RED));
            }
        }
    }

    private void showRecipeInfo(Player player) {
        AbstractVillager villager = getTargetVillager(player);
        if (villager == null) return;

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        int[] originalCosts = pdc.get(originalCostsKey, PersistentDataType.INTEGER_ARRAY);
        int[] originalResults = pdc.get(originalResultsKey, PersistentDataType.INTEGER_ARRAY);

        player.sendMessage(Component.text("=== Recipe Conversion Details ===").color(NamedTextColor.YELLOW));
        
        for (int i = 0; i < villager.getRecipeCount(); i++) {
            MerchantRecipe recipe = villager.getRecipe(i);
            player.sendMessage(Component.text("Recipe " + (i + 1) + ":").color(NamedTextColor.GOLD));
            
            // Show original costs if available
            if (originalCosts != null && i < originalCosts.length) {
                player.sendMessage(Component.text("  Original Cost: ").color(NamedTextColor.WHITE)
                        .append(Component.text(originalCosts[i] + " emeralds").color(NamedTextColor.YELLOW)));
            }
            
            // Show current ingredients
            player.sendMessage(Component.text("  Current Ingredients:").color(NamedTextColor.WHITE));
            for (ItemStack ingredient : recipe.getIngredients()) {
                String itemInfo = getItemInfo(ingredient);
                player.sendMessage(Component.text("    - " + itemInfo).color(NamedTextColor.GRAY));
            }
            
            // Show original results if available
            if (originalResults != null && i < originalResults.length) {
                player.sendMessage(Component.text("  Original Result: ").color(NamedTextColor.WHITE)
                        .append(Component.text(originalResults[i] + " emeralds").color(NamedTextColor.YELLOW)));
            }
            
            // Show current result
            ItemStack result = recipe.getResult();
            String resultInfo = getItemInfo(result);
            player.sendMessage(Component.text("  Current Result: ").color(NamedTextColor.WHITE)
                    .append(Component.text(resultInfo).color(NamedTextColor.GREEN)));
            
            // Show conversion calculation
            if (originalCosts != null && i < originalCosts.length) {
                int originalEmeralds = originalCosts[i];
                int convertedCoppets = emeraldsToCoppets(originalEmeralds);
                player.sendMessage(Component.text("  Conversion: ").color(NamedTextColor.WHITE)
                        .append(Component.text(originalEmeralds + " emeralds → " + convertedCoppets + " coppets")
                                .color(NamedTextColor.YELLOW)));
            }
        }
    }

    private void showConfigInfo(Player player) {
        player.sendMessage(Component.text("=== Plugin Configuration ===").color(NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Version: ").color(NamedTextColor.WHITE)
                .append(Component.text(plugin.getConfig().getInt("villagers.version")).color(NamedTextColor.GOLD)));
        player.sendMessage(Component.text("Emeralds to Coppets Multiplier: ").color(NamedTextColor.WHITE)
                .append(Component.text(plugin.getConfig().getDouble("villagers.emeraldsToCoppetsMultiplier")).color(NamedTextColor.AQUA)));
        player.sendMessage(Component.text("Use Coins Only for Rare Items: ").color(NamedTextColor.WHITE)
                .append(Component.text(plugin.getConfig().getBoolean("villagers.useCoinsOnlyForRareItems") ? "YES" : "NO")
                        .color(plugin.getConfig().getBoolean("villagers.useCoinsOnlyForRareItems") ? NamedTextColor.GREEN : NamedTextColor.RED)));
        player.sendMessage(Component.text("Convert Trade Results: ").color(NamedTextColor.WHITE)
                .append(Component.text(plugin.getConfig().getBoolean("villagers.convertTradeResults") ? "YES" : "NO")
                        .color(plugin.getConfig().getBoolean("villagers.convertTradeResults") ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }

    private AbstractVillager getTargetVillager(Player player) {
        AbstractVillager villager = null;
        
        // Try to get the villager the player is looking at
        if (player.getTargetEntity(5) instanceof AbstractVillager target) {
            villager = target;
        } else {
            // Find the closest villager within 3 blocks
            List<AbstractVillager> nearbyVillagers = player.getNearbyEntities(3, 3, 3).stream()
                    .filter(entity -> entity instanceof AbstractVillager)
                    .map(entity -> (AbstractVillager) entity)
                    .toList();
            
            if (!nearbyVillagers.isEmpty()) {
                villager = nearbyVillagers.getFirst();
            }
        }
        
        if (villager == null) {
            player.sendMessage(Component.text("No villager found! Look at a villager or get closer to one.").color(NamedTextColor.RED));
        }
        
        return villager;
    }

    private String getItemInfo(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "AIR";
        
        StringBuilder info = new StringBuilder();
        info.append(item.getAmount()).append("x ").append(item.getType().name());
        
        CoinType coinType = CoinUtil.getCoinType(item);
        if (coinType != CoinType.NONE) {
            info.append(" (").append(coinType.name()).append(")");
        }
        
        String itemUtilType = ItemUtil.getType(item);
        if (itemUtilType != null && !itemUtilType.isEmpty()) {
            info.append(" [").append(itemUtilType).append("]");
        }
        
        return info.toString();
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