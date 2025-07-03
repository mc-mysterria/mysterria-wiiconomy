package dev.ua.ikeepcalm.wiic.guis.shop;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.economy.models.Shop;
import dev.ua.ikeepcalm.wiic.economy.models.ShopItem;
import dev.ua.ikeepcalm.wiic.currency.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.item.LegacyItemUtil;
import dev.ua.ikeepcalm.wiic.utils.network.Requester;
import dev.ua.ikeepcalm.wiic.economy.vault.VaultUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShopGUI {
    private final WIIC plugin;
    private final Player player;
    private final Shop shop;
    private static final Map<String, Shop> shopCache = new HashMap<>();

    public ShopGUI(WIIC plugin, Player player, String name) {
        this.plugin = plugin;
        this.player = player;
        if (shopCache.containsKey(name)) {
            this.shop = shopCache.get(name);
        } else {
            this.shop = Shop.fromConfig(YamlConfiguration.loadConfiguration(
                    plugin.getDataFolder().toPath().resolve("shops").resolve(name + ".yml").toFile()
            ));
            shopCache.put(name, shop);
        }
    }

    public void open(boolean useCoppets) {
        final ChestGui gui = new ChestGui(shop.rows(), shop.title());
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        int size = 9 * (shop.rows() - 1);
        final OutlinePane pane = new OutlinePane(9, shop.rows());

        for (ShopItem shopItem : shop.items()) {
            if (useCoppets && shopItem.coppetPrice() == -1) continue;
            size--;
            final ItemStack item = shopItem.item().clone();
            final ItemMeta meta = item.getItemMeta();
            meta.lore(List.of(
                    Component.text(
                                    useCoppets ? "Ціна: " + CoinUtil.getFormattedPrice(shopItem.coppetPrice())
                                            : "Ціна: " + shopItem.price() + " UNI").color(NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(shopItem.description()).decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
            pane.addItem(new GuiItem(item, event -> Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> buyItem(shopItem.item().clone(), useCoppets ? shopItem.coppetPrice() : shopItem.price(), useCoppets)
            )));
        }

        for (int i = 0; i < size; i++) {
            pane.addItem(new GuiItem(new ItemStack(Material.AIR)));
        }
        for (int i = 0; i < 4; i++) {
            pane.addItem(new GuiItem(getFillerItem()));
        }
        pane.addItem(new GuiItem(getCurrencySwitcherItem(useCoppets), event -> open(!useCoppets)));
        for (int i = 0; i < 4; i++) {
            pane.addItem(new GuiItem(getFillerItem()));
        }

        gui.addPane(pane);
        gui.show(player);
    }

    private void buyItem(ItemStack item, int price, boolean useCoppets) {
        final String itemName = PlainTextComponentSerializer.plainText().serialize(item.displayName());
        plugin.getLogger().info(player.getName() + " has bought " + itemName + " for " + price);
        if (!useCoppets) {
            try {
                String userId = Requester.fetchUser(player.getName()).getId();
                Requester.modifyBalance(userId, userId, -price, "Купівля предмета: " + itemName);
            } catch (Exception e) {
                e.printStackTrace();
                player.sendMessage(Component.text("Під час придбання предмета відбулася помилка!").color(NamedTextColor.RED));
                return;
            }
        } else {
            if (VaultUtil.getBalance(player.getUniqueId()).join() >= price) {
                VaultUtil.withdraw(player.getUniqueId(), price);
            } else {
                player.sendMessage(Component.text("Недостатньо коштів в гаманці!").color(NamedTextColor.RED));
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> LegacyItemUtil.giveOrDrop(player, item));
        player.sendMessage(Component.text("Ви успішно придбали предмет!").color(NamedTextColor.GREEN));
    }

    private ItemStack getFillerItem() {
        final ItemStack item = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        final ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getCurrencySwitcherItem(boolean useCoppets) {
        final ItemStack item = new ItemStack(useCoppets ? Material.GOLD_INGOT : Material.DIAMOND);
        final ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Валюта: " + (useCoppets ? "копійки" : "UNI")).color(NamedTextColor.GREEN));
        item.setItemMeta(meta);
        return item;
    }

    public static void clearShopCache() {
        shopCache.clear();
    }
}
