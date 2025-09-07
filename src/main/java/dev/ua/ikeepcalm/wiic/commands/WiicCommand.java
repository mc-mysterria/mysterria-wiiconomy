package dev.ua.ikeepcalm.wiic.commands;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
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
import org.bukkit.entity.Player;
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
            sender.sendMessage("§cВи не маєте дозволу на використання цієї команди!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§eВикористання: /wiic <reload|restore|debug|version>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reloadConfig();
                sender.sendMessage("§aКонфігурацію перезавантажено!");
                plugin.getLogger().log(Level.INFO, "Configuration reloaded by " + sender.getName());
                break;

            case "restore":
                restoreVillagerTrades(sender);
                break;

            case "debug":
                boolean currentState = plugin.getConfig().getBoolean("debug.villagerListener", false);
                plugin.getConfig().set("debug.villagerListener", !currentState);
                plugin.saveConfig();
                sender.sendMessage("§eДебаг режим " + (!currentState ? "§aувімкнено" : "§cвимкнено") + "§e!");
                plugin.getLogger().log(Level.INFO, "Debug mode " + (!currentState ? "enabled" : "disabled") + " by " + sender.getName());
                break;

            case "version":
                checkVillagerVersions(sender);
                break;

            default:
                sender.sendMessage("§eВикористання: /wiic <reload|restore|debug|version>");
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

        sender.sendMessage("§aВідновлено торгівлю для §e" + restoredCount + "§a з §e" + totalVillagers + "§a жителів!");
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

        sender.sendMessage("§e=== Звіт версій жителів ===");
        sender.sendMessage("§eПоточна версія конфігурації: §a" + currentVersion);
        sender.sendMessage("");

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
                sender.sendMessage("§6Світ " + world.getName() + "§e:");
                sender.sendMessage("  §aАктуальні: " + worldMatching);
                sender.sendMessage("  §cЗастарілі: " + worldOutdated);
                sender.sendMessage("  §7Невідомі: " + worldUnknown);
                sender.sendMessage("  §eВсього: " + worldVillagers);
                sender.sendMessage("");
            }
        }

        sender.sendMessage("§e=== Загальна статистика ===");
        sender.sendMessage("§aАктуальні жителі: " + matchingVillagers + "/" + totalVillagers);
        sender.sendMessage("§cЗастарілі жителі: " + outdatedVillagers + "/" + totalVillagers);
        sender.sendMessage("§7Жителі без версії: " + unknownVillagers + "/" + totalVillagers);
        
        if (outdatedVillagers > 0 || unknownVillagers > 0) {
            sender.sendMessage("");
            sender.sendMessage("§eТоргівля буде оновлена при наступній взаємодії з жителями.");
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