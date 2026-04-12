package dev.ua.ikeepcalm.wiic.gui;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import dev.ua.ikeepcalm.wiic.currency.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.currency.services.SoldItemsManager;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents the main Wallet GUI where players can see their balance, stats, and navigate to other menus.
 * This class is designed to be highly configurable via config.yml.
 */
public class WalletGUI {

    public final static Set<Player> playersWithOpenWallets = new HashSet<>();
    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;
    private final ConfigurationSection config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private boolean callOnClose = true;

    public WalletGUI(PriceAppraiser priceAppraiser, SoldItemsManager soldItemsManager) {
        this.priceAppraiser = priceAppraiser;
        this.soldItemsManager = soldItemsManager;
        this.config = WIIC.INSTANCE.getConfig().getConfigurationSection("wallet-gui");
    }

    /**
     * Opens the Wallet GUI for a player.
     *
     * @param player  The player to open the GUI for.
     * @param data    The wallet data (e.g. balance).
     * @param onClose Action to perform when the GUI is closed.
     */
    public void open(Player player, WalletData data, Runnable onClose) {
        if (config == null) {
            player.sendMessage(Component.text("Wallet GUI configuration is missing!").color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        String titleStr = config.getString("title", "Wallet");
        ChestGui gui = new ChestGui(3, ComponentHolder.of(miniMessage.deserialize(parsePlaceholders(player, titleStr))));
        StaticPane pane = new StaticPane(9, 3);

        // Add items from configuration
        addConfigItem(pane, "player-info", player, null);
        addConfigItem(pane, "balance", player, click -> {
            callOnClose = false;
            player.closeInventory();
            new VaultGUI(priceAppraiser, soldItemsManager).openVault(player, onClose);
        });
        addConfigItem(pane, "rank", player, null);
        addConfigItem(pane, "documentation", player, null);
        addConfigItem(pane, "store", player, null);
        addConfigItem(pane, "discord", player, null);
        addConfigItem(pane, "server-stats", player, null);

        gui.addPane(Slot.fromXY(0, 0), pane);

        gui.setOnClose(event -> {
            if (callOnClose) {
                onClose.run();
            }
        });

        gui.setOnGlobalClick(click -> click.setCancelled(true));
        gui.show(player);
    }

    /**
     * Adds an item to the pane based on config.yml settings.
     */
    private void addConfigItem(StaticPane pane, String key, Player player, java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> action) {
        ConfigurationSection itemSection = config.getConfigurationSection("items." + key);
        if (itemSection == null) return;

        List<Integer> slot = itemSection.getIntegerList("slot");
        if (slot.size() < 2) return;

        ItemStack item = createConfigItem(itemSection, player);
        pane.addItem(new GuiItem(item, click -> {
            click.setCancelled(true);
            if (action != null) {
                action.accept(click);
            }
        }), slot.get(0), slot.get(1));
    }

    /**
     * Creates an ItemStack based on a configuration section.
     */
    private ItemStack createConfigItem(ConfigurationSection section, Player player) {
        String materialName = section.getString("material", "DIAMOND");
        Material material = Material.matchMaterial(materialName);
        ItemStack item = new ItemStack(material != null ? material : Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) return item;

        String name = section.getString("name");
        if (name != null) {
            meta.displayName(miniMessage.deserialize(parsePlaceholders(player, name))
                    .decoration(TextDecoration.ITALIC, false));
        }

        List<String> lore = section.getStringList("lore");
        if (!lore.isEmpty()) {
            meta.lore(lore.stream()
                    .map(line -> miniMessage.deserialize(parsePlaceholders(player, line))
                            .decoration(TextDecoration.ITALIC, false))
                    .collect(Collectors.toList()));
        }

        // Support for setItemModel (NamespacedKey)
        String itemModel = section.getString("item-model");
        if (itemModel != null && !itemModel.isEmpty()) {
            if (itemModel.contains(":")) {
                meta.setItemModel(NamespacedKey.fromString(itemModel));
            } else {
                meta.setItemModel(new NamespacedKey(WIIC.INSTANCE, itemModel));
            }
        }

        // Support for CustomModelData
        int cmd = section.getInt("custom-model-data", -1);
        if (cmd != -1) {
            meta.setCustomModelData(cmd);
        }

        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
        }

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Parses placeholders using PlaceholderAPI if available, and our internal placeholders.
     */
    private String parsePlaceholders(Player player, String text) {
        if (text == null) return "";

        // Internal Placeholders
        text = text.replace("%player%", player.getName());

        double balance = 0;
        if (WIIC.getEcon() != null) {
            balance = WIIC.getEcon().balance("iConomyUnlocked", player.getUniqueId()).doubleValue();
        }
        text = text.replace("%balance%", String.format("%,.2f", balance));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        text = text.replace("%first-join%", sdf.format(new Date(player.getFirstPlayed())));

        double tps = Bukkit.getTPS()[0];
        text = text.replace("%tps%", String.format("%.1f", tps));
        text = text.replace("%ping%", String.valueOf(player.getPing()));

        long ramUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        long ramMax = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        text = text.replace("%ram-used%", String.valueOf(ramUsed));
        text = text.replace("%ram-max%", String.valueOf(ramMax));

        text = text.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        text = text.replace("%max-players%", String.valueOf(Bukkit.getMaxPlayers()));

        // PlaceholderAPI Support
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            text = PlaceholderAPI.setPlaceholders(player, text);
        }

        return text;
    }
}
