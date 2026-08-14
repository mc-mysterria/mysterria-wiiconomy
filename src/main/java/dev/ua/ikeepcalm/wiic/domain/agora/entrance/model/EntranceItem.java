package dev.ua.ikeepcalm.wiic.domain.agora.entrance.model;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.EntranceService;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

import java.util.List;

/**
 * The craftable "secret entrance" item (PDC {@code wiic:type = market_entrance},
 * following the coin-item convention in {@code CoinUtil}). Right-clicking a block
 * with it inside a claimed Land builds and registers a market entrance door —
 * see {@link EntranceService#place}.
 */
public class EntranceItem {

    public static final String TYPE = "market_entrance";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private EntranceItem() {}

    public static ItemStack create(MarketConfig config) {
        ConfigurationSection section = config.raw().getConfigurationSection("entrance.item");
        Material material = Material.matchMaterial(
                section != null ? section.getString("material", "CRIMSON_DOOR") : "CRIMSON_DOOR");
        ItemStack item = new ItemStack(material != null ? material : Material.CRIMSON_DOOR);
        ItemUtil.setType(item, TYPE);
        item.editMeta(meta -> {
            String name = section != null
                    ? section.getString("name", "<dark_purple>sᴇᴄʀᴇᴛ ᴇɴᴛʀᴀɴᴄᴇ")
                    : "<dark_purple>sᴇᴄʀᴇᴛ ᴇɴᴛʀᴀɴᴄᴇ";
            meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<String> lore = section != null ? section.getStringList("lore") : List.of();
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                        .map(line -> line.isEmpty()
                                ? Component.empty()
                                : (Component) MM.deserialize(line).decoration(TextDecoration.ITALIC, false))
                        .toList());
            }
            String model = section != null ? section.getString("item-model") : null;
            if (model != null && !model.isEmpty()) {
                meta.setItemModel(model.contains(":")
                        ? NamespacedKey.fromString(model)
                        : new NamespacedKey(WIIC.INSTANCE, model));
            }
        });
        return item;
    }

    public static boolean isEntranceItem(ItemStack item) {
        return item != null && item.hasItemMeta() && TYPE.equals(ItemUtil.getType(item));
    }

    /** Registers the shaped recipe from {@code entrance.recipe} in market.yml, if crafting is enabled. */
    public static void registerRecipe(WIIC plugin, MarketConfig config) {
        if (!config.entranceCraftable()) return;
        List<String> shape = config.entranceRecipeShape();
        ConfigurationSection ingredients = config.entranceRecipeIngredients();
        if (shape.isEmpty() || ingredients == null) {
            plugin.getLogger().warning("Market entrance recipe not configured; item is admin-give only");
            return;
        }
        NamespacedKey key = new NamespacedKey(plugin, TYPE);
        Bukkit.removeRecipe(key);
        // shape()/setIngredient() throw on a shape the admin mistyped (ragged rows, a token
        // with no ingredient). That must cost the recipe, not the whole market module — the
        // item is still obtainable via /wiicmarket give-entrance.
        try {
            ShapedRecipe recipe = new ShapedRecipe(key, create(config));
            recipe.shape(shape.toArray(new String[0]));
            for (String token : ingredients.getKeys(false)) {
                if (token.length() != 1) continue;
                Material material = Material.matchMaterial(ingredients.getString(token, ""));
                if (material != null) recipe.setIngredient(token.charAt(0), new RecipeChoice.MaterialChoice(material));
            }
            Bukkit.addRecipe(recipe);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Market entrance recipe in market.yml is invalid ("
                    + e.getMessage() + "); the item is admin-give only");
        }
    }
}
