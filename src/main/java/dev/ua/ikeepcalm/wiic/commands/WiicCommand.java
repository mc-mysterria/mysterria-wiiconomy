package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class WiicCommand implements CommandExecutor, TabCompleter {

    private final WIIC plugin;
    private final NamespacedKey originalCostsKey;
    private final NamespacedKey originalResultsKey;
    private final NamespacedKey villagerVersionKey;

    public WiicCommand(WIIC plugin) {
        this.plugin = plugin;
        this.originalCostsKey = new NamespacedKey(plugin, "original_costs");
        this.originalResultsKey = new NamespacedKey(plugin, "original_results");
        this.villagerVersionKey = new NamespacedKey(plugin, "version");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wiic.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command!")
                    .color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /wiic <reload|restore|debug|version>")
                    .color(NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reloadConfig();
                sender.sendMessage(Component.text("Configuration reloaded!")
                        .color(NamedTextColor.GREEN));
                plugin.getLogger().log(Level.INFO, "Configuration reloaded by " + sender.getName());
                break;

            case "restore":
                restoreVillagerTrades(sender);
                break;

            case "debug":
                boolean currentState = plugin.getConfig().getBoolean("debug.villager-listener", false);
                plugin.getConfig().set("debug.villager-listener", !currentState);
                plugin.saveConfig();
                sender.sendMessage(Component.text("Debug mode ")
                        .color(NamedTextColor.YELLOW)
                        .append(Component.text(!currentState ? "enabled" : "disabled")
                                .color(!currentState ? NamedTextColor.GREEN : NamedTextColor.RED))
                        .append(Component.text("!").color(NamedTextColor.YELLOW)));
                plugin.getLogger().log(Level.INFO, "Debug mode " + (!currentState ? "enabled" : "disabled") + " by " + sender.getName());
                break;

            case "version":
                checkVillagerVersions(sender);
                break;

            default:
                sender.sendMessage(Component.text("Usage: /wiic <reload|restore|debug|version>")
                        .color(NamedTextColor.YELLOW));
                break;
        }

        return true;
    }

    private void restoreVillagerTrades(CommandSender sender) {
        int restoredCount = 0;
        int totalVillagers = 0;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof AbstractVillager villager) {
                    totalVillagers++;
                    if (restoreVillagerTrades(villager)) {
                        restoredCount++;
                    }
                }
            }
        }

        sender.sendMessage(Component.text("Restored trades for ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(restoredCount).color(NamedTextColor.YELLOW))
                .append(Component.text(" out of ").color(NamedTextColor.GREEN))
                .append(Component.text(totalVillagers).color(NamedTextColor.YELLOW))
                .append(Component.text(" villagers!").color(NamedTextColor.GREEN)));
        plugin.getLogger().log(Level.INFO, "Restored trades for " + restoredCount + " out of " + totalVillagers + " villagers by " + sender.getName());
    }

    private boolean restoreVillagerTrades(AbstractVillager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        int[] originalCosts = pdc.get(originalCostsKey, PersistentDataType.INTEGER_ARRAY);
        int[] originalResults = pdc.get(originalResultsKey, PersistentDataType.INTEGER_ARRAY);

        if (originalCosts == null || originalResults == null) {
            return false;
        }

        boolean restored = false;
        for (int i = 0; i < Math.min(villager.getRecipeCount(), originalCosts.length); i++) {
            MerchantRecipe recipe = villager.getRecipe(i);
            List<ItemStack> ingredients = recipe.getIngredients();

            // Remove existing coins and emeralds
            ingredients.removeIf(ingredient -> ingredient.getType() == Material.EMERALD || CoinUtil.isCoin(ingredient));

            // Add back original emerald costs
            int emeralds = originalCosts[i];
            if (emeralds > 64) {
                emeralds -= 64;
                ingredients.add(new ItemStack(Material.EMERALD, 64));
            }
            if (emeralds > 0) {
                ingredients.add(new ItemStack(Material.EMERALD, emeralds));
            }

            // Restore original result if it was emeralds
            ItemStack result = recipe.getResult();
            if ((result.getType() == Material.EMERALD || CoinUtil.isCoin(result)) && originalResults[i] > 0) {
                result = new ItemStack(Material.EMERALD, originalResults[i]);
            }

            MerchantRecipe newRecipe = new MerchantRecipe(
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
            newRecipe.setIngredients(ingredients);

            villager.setRecipe(i, newRecipe);
            restored = true;
        }

        if (restored) {
            // Reset version so trades will be re-converted on next interaction
            pdc.set(villagerVersionKey, PersistentDataType.INTEGER, 0);
        }

        return restored;
    }

    private void checkVillagerVersions(CommandSender sender) {
        int currentVersion = plugin.getConfig().getInt("villagers.version");
        int matchingVillagers = 0;
        int outdatedVillagers = 0;
        int unknownVillagers = 0;
        int totalVillagers = 0;

        sender.sendMessage(Component.text("=== Villager Version Report ===")
                .color(NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Current configuration version: ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text(currentVersion).color(NamedTextColor.GREEN)));
        sender.sendMessage(Component.empty());

        for (World world : Bukkit.getWorlds()) {
            int worldVillagers = 0;
            int worldMatching = 0;
            int worldOutdated = 0;
            int worldUnknown = 0;

            for (Entity entity : world.getEntities()) {
                if (entity instanceof AbstractVillager villager) {
                    totalVillagers++;
                    worldVillagers++;

                    PersistentDataContainer pdc = villager.getPersistentDataContainer();
                    Integer villagerVersion = pdc.get(villagerVersionKey, PersistentDataType.INTEGER);

                    if (villagerVersion == null) {
                        unknownVillagers++;
                        worldUnknown++;
                    } else if (villagerVersion.equals(currentVersion)) {
                        matchingVillagers++;
                        worldMatching++;
                    } else {
                        outdatedVillagers++;
                        worldOutdated++;
                    }
                }
            }

            if (worldVillagers > 0) {
                sender.sendMessage(Component.text("World ")
                        .color(NamedTextColor.GOLD)
                        .append(Component.text(world.getName()).color(NamedTextColor.GOLD))
                        .append(Component.text(":").color(NamedTextColor.YELLOW)));
                sender.sendMessage(Component.text("  Up-to-date: " + worldMatching)
                        .color(NamedTextColor.GREEN));
                sender.sendMessage(Component.text("  Outdated: " + worldOutdated)
                        .color(NamedTextColor.RED));
                sender.sendMessage(Component.text("  Unknown: " + worldUnknown)
                        .color(NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  Total: " + worldVillagers)
                        .color(NamedTextColor.YELLOW));
                sender.sendMessage(Component.empty());
            }
        }

        sender.sendMessage(Component.text("=== Overall Statistics ===")
                .color(NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Up-to-date villagers: " + matchingVillagers + "/" + totalVillagers)
                .color(NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Outdated villagers: " + outdatedVillagers + "/" + totalVillagers)
                .color(NamedTextColor.RED));
        sender.sendMessage(Component.text("Villagers without version: " + unknownVillagers + "/" + totalVillagers)
                .color(NamedTextColor.GRAY));

        if (outdatedVillagers > 0 || unknownVillagers > 0) {
            sender.sendMessage(Component.empty());
            sender.sendMessage(Component.text("Trades will be updated on next villager interaction.")
                    .color(NamedTextColor.YELLOW));
        }

        plugin.getLogger().log(Level.INFO, "Villager version check by " + sender.getName() + ": " + matchingVillagers + " current, " + outdatedVillagers + " outdated, " + unknownVillagers + " unknown");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("reload", "restore", "debug", "version");
        }
        return null;
    }
}