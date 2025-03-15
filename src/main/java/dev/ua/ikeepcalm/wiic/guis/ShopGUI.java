package dev.ua.ikeepcalm.wiic.guis;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import dev.ua.ikeepcalm.market.util.ItemStackUtil;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.economy.Shop;
import dev.ua.ikeepcalm.wiic.economy.ShopItem;
import dev.ua.ikeepcalm.wiic.utils.Requester;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
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

    public void open() {
        final ChestGui gui = new ChestGui(shop.getRows(), shop.getTitle());
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        final OutlinePane pane = new OutlinePane(9, shop.getRows());

        for (ShopItem shopItem : shop.getItems()) {
            final ItemStack item = shopItem.item().clone();
            final ItemMeta meta = item.getItemMeta();
            meta.lore(List.of(
                    Component.text("Ціна: " + shopItem.price()).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                    Component.text(shopItem.description()).decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
            pane.addItem(new GuiItem(item, event -> Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> buyItem(shopItem.item().clone(), shopItem.price())
            )));
        }

        gui.addPane(pane);
        gui.show(player);
    }

    private void buyItem(ItemStack item, int price) {
        final String itemName = PlainTextComponentSerializer.plainText().serialize(item.displayName());
        plugin.getLogger().info(player.getName() + " has bought " + itemName + " for " + price);
        try {
            int userId = Requester.fetchUser(player.getName()).getId();
            Requester.modifyBalance(userId, userId, -price, "Купівля предмета: " + itemName);
        } catch (Exception e) {
            e.printStackTrace();
            player.sendMessage(Component.text("Під час придбання предмета відбулася помилка!").color(NamedTextColor.RED));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> ItemStackUtil.giveOrDrop(player, item));
        player.sendMessage(Component.text("Ви успішно придбали предмет!").color(NamedTextColor.GREEN));
    }
}
