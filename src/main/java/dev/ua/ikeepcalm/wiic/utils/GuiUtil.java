package dev.ua.ikeepcalm.wiic.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GuiUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final TextColor TITLE = TextColor.color(0xF0CC55);
    private static final NamespacedKey TOOLTIP = new NamespacedKey("wiic", "ledger");
    private static final int TITLE_WIDTH = 166;
    private static final String ELLIPSIS = "…";
    private static final Map<String, String> BUTTON_ICONS = Map.ofEntries(
            Map.entry("back", "sacredorder:gui/back"),
            Map.entry("previous-page", "sacredorder:gui/left"),
            Map.entry("next-page", "sacredorder:gui/right"),
            Map.entry("page-indicator", "sacredorder:gui/info"),
            Map.entry("confirm", "sacredorder:gui/check"),
            Map.entry("confirm-available", "sacredorder:gui/check"),
            Map.entry("confirm-unavailable", "sacredorder:gui/lock"),
            Map.entry("cancel", "sacredorder:gui/close"),
            Map.entry("search", "wiic:gui/search"),
            Map.entry("sort", "sacredorder:gui/stash_sort"),
            Map.entry("claim-all", "sacredorder:gui/coin"),
            Map.entry("claim", "sacredorder:gui/coin"),
            Map.entry("my-listings", "sacredorder:gui/stash_log"),
            Map.entry("allowance", "sacredorder:gui/goal"),
            Map.entry("info", "sacredorder:gui/goal"),
            Map.entry("withdraw", "sacredorder:gui/up"),
            Map.entry("extend", "sacredorder:gui/coin"),
            Map.entry("release", "sacredorder:gui/unlocks"),
            Map.entry("price-display", "sacredorder:gui/coin"),
            Map.entry("type-price", "sacredorder:gui/commissions"),
            Map.entry("type-amount", "sacredorder:gui/commissions"),
            Map.entry("balance", "wallet:balance"),
            Map.entry("market-index", "wallet:pages"),
            Map.entry("preset", "wallet:block_2"),
            Map.entry("decrease", "sacredorder:gui/minus"),
            Map.entry("increase", "sacredorder:gui/plus"));

    private GuiUtil() {}

    public static boolean textured() {
        return Bukkit.getPluginManager().isPluginEnabled("Nexo");
    }

    public static Component title(Player player, String template, Map<String, String> extras, int rows) {
        Component text = MM.deserialize(replacePlaceholders(player, template, fitTitle(player, template, extras)));
        if (!textured() || template.contains("<glyph:")) return text;
        return MM.deserialize("<shift:-15><glyph:wiic_board_" + rows + "><shift:-176>").append(text.colorIfAbsent(TITLE));
    }

    private static Map<String, String> fitTitle(Player player, String template, Map<String, String> extras) {
        List<String> used = extras.keySet().stream().filter(template::contains).toList();
        if (used.isEmpty()) return extras;
        Map<String, String> blanks = new HashMap<>();
        used.forEach(key -> blanks.put(key, ""));
        int room = (TITLE_WIDTH - textWidth(plain(replacePlaceholders(player, template, blanks)))) / used.size();
        Map<String, String> fitted = new HashMap<>(extras);
        used.forEach(key -> fitted.put(key, ellipsize(extras.get(key), room)));
        return fitted;
    }

    private static String ellipsize(String text, int room) {
        if (textWidth(text) <= room) return text;
        int budget = room - textWidth(ELLIPSIS);
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (textWidth(kept) + charWidth(cp) > budget) break;
            kept.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return kept.toString().stripTrailing() + ELLIPSIS;
    }

    private static String plain(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MM.deserialize(miniMessage));
    }

    private static int textWidth(CharSequence text) {
        return text.codePoints().map(GuiUtil::charWidth).sum();
    }

    private static int charWidth(int cp) {
        if ("!',.:;i|".indexOf(cp) >= 0) return 2;
        if ("`l".indexOf(cp) >= 0) return 3;
        if (" \"()*I[]t{}ɪ".indexOf(cp) >= 0) return 4;
        if ("<>fk".indexOf(cp) >= 0) return 5;
        if ("@~«»".indexOf(cp) >= 0) return 7;
        if (cp == '…') return 8;
        return 6;
    }

    public static ItemStack emptyPane(Material material) {
        if (textured()) return ItemStack.empty();
        ItemStack pane = new ItemStack(material);
        pane.editMeta(meta -> meta.displayName(Component.empty()));
        return pane;
    }

    public static Material backgroundMaterial(ConfigurationSection config) {
        if (config == null) return Material.GRAY_STAINED_GLASS_PANE;
        String name = config.getString("background", "GRAY_STAINED_GLASS_PANE");
        Material m = Material.matchMaterial(name);
        return m != null ? m : Material.AIR;
    }

    public static void styleButton(ItemStack item, String key) {
        if (!textured() || item.isEmpty()) return;
        String icon = BUTTON_ICONS.get(key.replaceFirst("-\\d+$", ""));
        item.editMeta(meta -> {
            meta.setTooltipStyle(TOOLTIP);
            if (icon != null && !meta.hasItemModel()) meta.setItemModel(NamespacedKey.fromString(icon));
        });
    }

    public static ItemStack createConfigItem(ConfigurationSection section, Player player) {
        return createConfigItem(section, player, Map.of());
    }

    public static ItemStack createConfigItem(ConfigurationSection section, Player player, Map<String, String> extras) {
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
        styleButton(item, section.getName());
        return item;
    }

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

    public static int slotIndex(List<Integer> slot) {
        if (slot == null || slot.size() < 2) return -1;
        return slot.get(1) * 9 + slot.get(0);
    }

    public static int slotIndex(ConfigurationSection section, String key) {
        if (section == null) return -1;
        return slotIndex(section.getIntegerList(key));
    }

    public static int itemSlot(ConfigurationSection itemSection) {
        return slotIndex(itemSection, "slot");
    }

    public static String prettify(String token) {
        String[] parts = token.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
