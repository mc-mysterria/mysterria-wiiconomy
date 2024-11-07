package dev.ua.ikeepcalm.wiic.guis;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.List;

public class ActionGUI {

    private final Player pl;
    private final ItemStack item;
    private final ConfirmationCallback callback;

    public ActionGUI(Player player, ItemStack item, ConfirmationCallback callback) {
        this.pl = player;
        this.item = item;
        this.callback = callback;
    }

    public void open() {
        ChestGui gui = new ChestGui(3, "Зняття/Поповнення");
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        ItemStack detailedItem = createItem(item.getType(), item.getType().name(), Collections.emptyList(), NamedTextColor.GOLD);
        GuiItem centralItem = new GuiItem(detailedItem);

        ItemStack confirmItem = createItem(Material.GREEN_WOOL, "Підтвердити", Collections.emptyList(), NamedTextColor.GREEN);
        GuiItem confirmGuiItem = new GuiItem(confirmItem, event -> {
            callback.onConfirm(item);
        });

        ItemStack cancelItem = createItem(Material.RED_WOOL, "Скасувати", Collections.emptyList(), NamedTextColor.RED);
        GuiItem cancelGuiItem = new GuiItem(cancelItem, event -> {
            callback.onCancel();
        });

        OutlinePane background = new OutlinePane(0, 0, 9, 3, Pane.Priority.LOWEST);
        background.addItem(new GuiItem(new ItemStack(Material.BLACK_STAINED_GLASS_PANE)));
        background.setRepeat(true);

        gui.addPane(background);

        OutlinePane navigationPane = new OutlinePane(3, 1, 3, 1);
        navigationPane.addItem(confirmGuiItem);
        navigationPane.addItem(centralItem);
        navigationPane.addItem(cancelGuiItem);
        gui.addPane(navigationPane);

        gui.show(pl);
    }

    private ItemStack createItem(Material material, String name, List<Component> lore, TextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public interface ConfirmationCallback {
        void onConfirm(ItemStack item);

        void onCancel();
    }
}
