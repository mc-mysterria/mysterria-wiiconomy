package dev.ua.ikeepcalm.wiic.gui;

import dev.ua.ikeepcalm.wiic.WIIC;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared GUI utilities — config item creation, placeholder parsing, slot math.
 * All methods are static; do not instantiate.
 */
public final class GuiUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private GuiUtil() {}

    // -------------------------------------------------------------------------
    // Background helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a nameless glass-pane filler item for GUI backgrounds.
     */
    public static ItemStack emptyPane(Material material) {
        ItemStack pane = new ItemStack(material);
        pane.editMeta(meta -> meta.displayName(Component.empty()));
        return pane;
    }

    /**
     * Reads {@code background} key from a config section and returns the
     * corresponding Material. Falls back to {@code GRAY_STAINED_GLASS_PANE}.
     */
    public static Material backgroundMaterial(ConfigurationSection config) {
        if (config == null) return Material.GRAY_STAINED_GLASS_PANE;
        String name = config.getString("background", "GRAY_STAINED_GLASS_PANE");
        Material m = Material.matchMaterial(name);
        return m != null ? m : Material.AIR;
    }

    // -------------------------------------------------------------------------
    // Item creation
    // -------------------------------------------------------------------------

    /**
     * Creates an ItemStack from a config section with no extra placeholders.
     * Supported keys: {@code material}, {@code name}, {@code lore},
     * {@code item-model}, {@code custom-model-data}.
     * Player-head skull owner is set automatically.
     */
    public static ItemStack createConfigItem(ConfigurationSection section, Player player) {
        return createConfigItem(section, player, Map.of());
    }

    /**
     * Creates an ItemStack from a config section, substituting {@code extras}
     * placeholders in addition to the standard set (see {@link #replacePlaceholders}).
     */
    public static ItemStack createConfigItem(ConfigurationSection section, Player player,
                                              Map<String, String> extras) {
        if (section == null) return new ItemStack(Material.STONE);

        String materialName = section.getString("material", "STONE");
        Material material = Material.matchMaterial(materialName);
        ItemStack item = new ItemStack(material != null ? material : Material.STONE);
        var meta = item.getItemMeta();
        if (meta == null) return item;

        String name = section.getString("name");
        if (name != null) {
            meta.displayName(MM.deserialize(replacePlaceholders(player, name, extras))
                    .decoration(TextDecoration.ITALIC, false));
        }

        List<String> lore = section.getStringList("lore");
        if (!lore.isEmpty()) {
            meta.lore(lore.stream()
                    .map(line -> line.isEmpty()
                            ? Component.empty()
                            : MM.deserialize(replacePlaceholders(player, line, extras))
                                    .decoration(TextDecoration.ITALIC, false))
                    .collect(Collectors.toList()));
        }

        String itemModel = section.getString("item-model");
        if (itemModel != null && !itemModel.isEmpty()) {
            meta.setItemModel(itemModel.contains(":")
                    ? NamespacedKey.fromString(itemModel)
                    : new NamespacedKey(WIIC.INSTANCE, itemModel));
        }

        int cmd = section.getInt("custom-model-data", -1);
        if (cmd != -1) meta.setCustomModelData(cmd);

        if (meta instanceof SkullMeta skullMeta) skullMeta.setOwningPlayer(player);

        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Placeholder parsing
    // -------------------------------------------------------------------------

    /**
     * Replaces built-in placeholders in {@code text}, then delegates any remaining
     * {@code %placeholder%} tokens to PlaceholderAPI if it is installed.
     *
     * <p>Built-in: {@code %player%}, {@code %balance%}, {@code %first-join%},
     * {@code %tps%}, {@code %ping%}, {@code %ram-used%}, {@code %ram-max%},
     * {@code %online%}, {@code %max-players%}, plus any entries in {@code extras}.
     */
    public static String replacePlaceholders(Player player, String text, Map<String, String> extras) {
        if (text == null) return "";

        text = text.replace("%player%", player.getName());

        double balance = WIIC.getEcon() != null
                ? WIIC.getEcon().balance("iConomyUnlocked", player.getUniqueId()).doubleValue()
                : 0d;
        text = text.replace("%balance%", String.format("%,.2f", balance));
        text = text.replace("%first-join%",
                new SimpleDateFormat("yyyy-MM-dd").format(new Date(player.getFirstPlayed())));
        text = text.replace("%tps%", String.format("%.1f", Bukkit.getTPS()[0]));
        text = text.replace("%ping%", String.valueOf(player.getPing()));

        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        text = text.replace("%ram-used%", String.valueOf(used));
        text = text.replace("%ram-max%", String.valueOf(Runtime.getRuntime().maxMemory() / 1024 / 1024));
        text = text.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        text = text.replace("%max-players%", String.valueOf(Bukkit.getMaxPlayers()));

        for (var entry : extras.entrySet()) text = text.replace(entry.getKey(), entry.getValue());

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            text = PlaceholderAPI.setPlaceholders(player, text);
        }
        return text;
    }

    // -------------------------------------------------------------------------
    // Slot math
    // -------------------------------------------------------------------------

    /**
     * Converts an {@code [x, y]} slot list (col, row, both 0-based) to an
     * InvUI slot index {@code y * 9 + x}. Returns {@code -1} for invalid input.
     */
    public static int slotIndex(List<Integer> slot) {
        if (slot == null || slot.size() < 2) return -1;
        return slot.get(1) * 9 + slot.get(0);
    }

    /**
     * Reads an {@code [x, y]} list at {@code key} from {@code section} and
     * converts it to an InvUI slot index.
     */
    public static int slotIndex(ConfigurationSection section, String key) {
        if (section == null) return -1;
        return slotIndex(section.getIntegerList(key));
    }

    /**
     * Reads an {@code [x, y]} list from an item sub-section's {@code slot} key.
     */
    public static int itemSlot(ConfigurationSection itemSection) {
        return slotIndex(itemSection, "slot");
    }
}
