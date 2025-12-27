package dev.ua.ikeepcalm.wiic.gui;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import dev.ua.ikeepcalm.wiic.locale.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.List;

public class ActionGUI {

    private final Player pl;
    private final ItemStack item;
    private final VaultGUI vaultGUI;
    private final ConfirmationCallback callback;
    private final Runnable onClose;

    public ActionGUI(Player player, ItemStack item, VaultGUI vaultGUI, ConfirmationCallback callback, Runnable onClose) {
        this.pl = player;
        this.item = item;
        this.vaultGUI = vaultGUI;
        this.callback = callback;
        this.onClose = onClose;
    }

    public void open() {
        Component title = MessageManager.getMessage("wiic.gui.action.title");
        ChestGui gui = new ChestGui(3, ComponentHolder.of(title));
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        // Enhanced central item with quantity and type info
        ItemStack detailedItem = item.clone();
        ItemMeta detailedMeta = detailedItem.getItemMeta();
        if (detailedMeta != null) {
            Component originalName = detailedMeta.hasDisplayName()
                ? detailedMeta.displayName()
                : Component.translatable(item.getType().translationKey());

            if (originalName != null) {
                detailedMeta.displayName(originalName
                        .color(TextColor.color(0xFFD700))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true));
            }

            detailedMeta.lore(List.of(
                    Component.empty(),
                    Component.translatable("wiic.gui.action.separator")
                            .color(TextColor.color(0xDAA520))
                            .decoration(TextDecoration.ITALIC, false),
                    Component.translatable("wiic.gui.action.amount", Component.text(item.getAmount()))
                            .color(NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.translatable("wiic.gui.action.hint")
                            .color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));
            detailedItem.setItemMeta(detailedMeta);
        }
        GuiItem centralItem = new GuiItem(detailedItem);

        // Enhanced confirm button
        ItemStack confirmItem = createEnhancedButton(
                Material.LIME_WOOL,
                "wiic.gui.action.confirm",
                TextColor.color(0x00FF00),
                List.of(
                        Component.empty(),
                        Component.translatable("wiic.gui.action.confirm_hint")
                                .color(NamedTextColor.GREEN)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.empty()
                )
        );
        GuiItem confirmGuiItem = new GuiItem(confirmItem, event -> {
            callback.onConfirm(item);
        });

        // Enhanced cancel button
        ItemStack cancelItem = createEnhancedButton(
                Material.RED_WOOL,
                "wiic.gui.action.cancel",
                TextColor.color(0xFF0000),
                List.of(
                        Component.empty(),
                        Component.translatable("wiic.gui.action.cancel_hint")
                                .color(NamedTextColor.RED)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.empty()
                )
        );
        GuiItem cancelGuiItem = new GuiItem(cancelItem, event -> {
            callback.onCancel();
        });

        // Gradient background
        setupGradientBackground(gui);

        OutlinePane navigationPane = new OutlinePane(2, 1, 5, 1, Pane.Priority.HIGH);
        navigationPane.addItem(confirmGuiItem);
        navigationPane.addItem(new GuiItem(createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE)));
        navigationPane.addItem(centralItem);
        navigationPane.addItem(new GuiItem(createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE)));
        navigationPane.addItem(cancelGuiItem);
        gui.addPane(navigationPane);

        gui.setOnClose(event -> {
            if (event.getReason() != InventoryCloseEvent.Reason.OPEN_NEW && event.getReason() != InventoryCloseEvent.Reason.PLUGIN) {
                vaultGUI.removeUUIDTags(pl.getInventory());
                onClose.run();
            }
        });

        gui.show(pl);
    }

    private void setupGradientBackground(ChestGui gui) {
        // Top and bottom borders
        OutlinePane top = new OutlinePane(0, 0, 9, 1, Pane.Priority.LOWEST);
        OutlinePane bottom = new OutlinePane(0, 2, 9, 1, Pane.Priority.LOWEST);
        top.addItem(new GuiItem(createGlassPane(Material.CYAN_STAINED_GLASS_PANE)));
        bottom.addItem(new GuiItem(createGlassPane(Material.CYAN_STAINED_GLASS_PANE)));
        top.setRepeat(true);
        bottom.setRepeat(true);

        // Side borders
        OutlinePane left = new OutlinePane(0, 1, 1, 1, Pane.Priority.LOWEST);
        OutlinePane right = new OutlinePane(8, 1, 1, 1, Pane.Priority.LOWEST);
        left.addItem(new GuiItem(createGlassPane(Material.LIGHT_BLUE_STAINED_GLASS_PANE)));
        right.addItem(new GuiItem(createGlassPane(Material.LIGHT_BLUE_STAINED_GLASS_PANE)));

        // Fill middle
        OutlinePane middle = new OutlinePane(1, 1, 1, 1, Pane.Priority.LOWEST);
        middle.addItem(new GuiItem(createGlassPane(Material.GRAY_STAINED_GLASS_PANE)));

        OutlinePane middleRight = new OutlinePane(7, 1, 1, 1, Pane.Priority.LOWEST);
        middleRight.addItem(new GuiItem(createGlassPane(Material.GRAY_STAINED_GLASS_PANE)));

        gui.addPane(top);
        gui.addPane(bottom);
        gui.addPane(left);
        gui.addPane(right);
        gui.addPane(middle);
        gui.addPane(middleRight);
    }

    private ItemStack createGlassPane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEnhancedButton(Material material, String translationKey, TextColor color, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.translatable(translationKey)
                .color(color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createItem(Material material, String name, List<Component> lore, TextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createTranslatableItem(Material material, String translationKey, List<Component> lore, TextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.translatable(translationKey).color(color).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public interface ConfirmationCallback {
        void onConfirm(ItemStack item);

        void onCancel();
    }
}
