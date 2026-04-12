package dev.ua.ikeepcalm.wiic.gui;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import dev.ua.ikeepcalm.wiic.currency.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.currency.services.SoldItemsManager;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SellingGUI {

    private final Player pl;
    private final ItemStack item;
    private final VaultGUI vaultGUI;
    private final ConfirmationCallback callback;
    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;
    private final Runnable onClose;

    public SellingGUI(
            Player player,
            ItemStack item,
            VaultGUI vaultGUI,
            ConfirmationCallback callback,
            PriceAppraiser priceAppraiser,
            SoldItemsManager soldItemsManager,
            Runnable onClose
    ) {
        this.pl = player;
        this.item = item;
        this.vaultGUI = vaultGUI;
        this.callback = callback;
        this.priceAppraiser = priceAppraiser;
        this.soldItemsManager = soldItemsManager;
        this.onClose = onClose;
    }

    public void open() {
        int appraisal = priceAppraiser.appraise(item);
        int availableAmount = soldItemsManager.getAvailableSellingAmount(pl);
        Component title = MessageManager.getMessage("wiic.gui.selling.title");
        ChestGui gui = new ChestGui(3, ComponentHolder.of(title));
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        if (appraisal > 0) {
            List<Component> details = priceAppraiser.getDetailedAppraisal(item, availableAmount);
            boolean canSell = appraisal <= availableAmount;

            // Enhanced item display
            ItemStack detailedItem = item.clone();
            ItemMeta detailedMeta = detailedItem.getItemMeta();
            if (detailedMeta != null) {
                Component originalName = detailedMeta.hasDisplayName()
                    ? detailedMeta.displayName()
                    : Component.translatable(item.getType().translationKey());

                if (originalName != null) {
                    detailedMeta.displayName(originalName
                            .color(canSell ? TextColor.color(0x00FF00) : TextColor.color(0xFF6B6B))
                            .decoration(TextDecoration.ITALIC, false)
                            .decoration(TextDecoration.BOLD, true));
                }

                List<Component> enhancedLore = new ArrayList<>();
                enhancedLore.add(Component.empty());
                enhancedLore.add(Component.translatable("wiic.gui.selling.separator")
                        .color(canSell ? TextColor.color(0x00AA00) : TextColor.color(0xAA0000))
                        .decoration(TextDecoration.ITALIC, false));
                enhancedLore.addAll(details);
                enhancedLore.add(Component.empty());
                if (canSell) {
                    enhancedLore.add(Component.translatable("wiic.gui.selling.ready")
                            .color(NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false));
                } else {
                    enhancedLore.add(Component.translatable("wiic.gui.selling.limit_exceeded")
                            .color(NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false));
                }
                enhancedLore.add(Component.empty());

                detailedMeta.lore(enhancedLore);
                detailedItem.setItemMeta(detailedMeta);
            }
            GuiItem centralItem = new GuiItem(detailedItem);

            // Enhanced buttons with better design
            ItemStack confirmItem = createEnhancedButton(
                    canSell ? Material.LIME_WOOL : Material.BARRIER,
                    canSell ? "wiic.gui.action.confirm" : "wiic.gui.selling.limit_reached",
                    canSell ? TextColor.color(0x00FF00) : TextColor.color(0xAA0000),
                    canSell
                        ? List.of(
                            Component.empty(),
                            Component.translatable("wiic.gui.selling.confirm_sell")
                                    .color(NamedTextColor.GREEN)
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.translatable("wiic.gui.selling.confirm_click")
                                    .color(NamedTextColor.GRAY)
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.empty()
                        )
                        : List.of(
                            Component.empty(),
                            Component.translatable("wiic.gui.selling.cannot_sell")
                                    .color(NamedTextColor.RED)
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.translatable("wiic.gui.selling.limit_reached_hint")
                                    .color(NamedTextColor.GRAY)
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.empty()
                        )
            );

            GuiItem confirmGuiItem = new GuiItem(confirmItem, event -> {
                if (canSell) {
                    callback.onConfirm(item);
                }
            });

            ItemStack cancelItem = createEnhancedButton(
                    Material.RED_WOOL,
                    "wiic.gui.action.cancel",
                    TextColor.color(0xFF0000),
                    List.of(
                            Component.empty(),
                            Component.translatable("wiic.gui.selling.cancel_transaction")
                                    .color(NamedTextColor.RED)
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.translatable("wiic.gui.selling.cancel_click")
                                    .color(NamedTextColor.GRAY)
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.empty()
                    )
            );
            GuiItem cancelGuiItem = new GuiItem(cancelItem, event -> {
                callback.onCancel();
            });

            // Setup gradient background (green theme for selling)
            setupSellingBackground(gui, canSell);

            OutlinePane navigationPane = new OutlinePane(5, 1, Pane.Priority.HIGH);
            navigationPane.addItem(confirmGuiItem);
            navigationPane.addItem(new GuiItem(createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE)));
            navigationPane.addItem(centralItem);
            navigationPane.addItem(new GuiItem(createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE)));
            navigationPane.addItem(cancelGuiItem);
            gui.addPane(Slot.fromXY(2, 1), navigationPane);

        } else {
            List<Component> details = priceAppraiser.getDetailedAppraisal(item, availableAmount);

            // Item cannot be sold
            ItemStack detailedItem = item.clone();
            ItemMeta detailedMeta = detailedItem.getItemMeta();
            if (detailedMeta != null) {
                Component originalName = detailedMeta.hasDisplayName()
                    ? detailedMeta.displayName()
                    : Component.translatable(item.getType().translationKey());

                if (originalName != null) {
                    detailedMeta.displayName(originalName
                            .color(TextColor.color(0xFF0000))
                            .decoration(TextDecoration.ITALIC, false)
                            .decoration(TextDecoration.BOLD, true));
                }

                List<Component> enhancedLore = new ArrayList<>();
                enhancedLore.add(Component.empty());
                enhancedLore.add(Component.translatable("wiic.gui.selling.item_info_separator")
                        .color(TextColor.color(0xAA0000))
                        .decoration(TextDecoration.ITALIC, false));
                enhancedLore.addAll(details);
                enhancedLore.add(Component.empty());
                enhancedLore.add(Component.translatable("wiic.gui.selling.unsellable")
                        .color(NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
                enhancedLore.add(Component.empty());

                detailedMeta.lore(enhancedLore);
                detailedItem.setItemMeta(detailedMeta);
            }
            GuiItem centralItem = new GuiItem(detailedItem);

            ItemStack cancelItem = createEnhancedButton(
                    Material.RED_WOOL,
                    "wiic.gui.action.cancel",
                    TextColor.color(0xFF0000),
                    List.of(
                            Component.empty(),
                            Component.translatable("wiic.gui.selling.go_back")
                                    .color(NamedTextColor.RED)
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.empty()
                    )
            );
            GuiItem cancelGuiItem = new GuiItem(cancelItem, event -> {
                callback.onCancel();
            });

            // Red-themed background for unsellable items
            setupSellingBackground(gui, false);

            OutlinePane navigationPane = new OutlinePane(3, 1, Pane.Priority.HIGH);
            navigationPane.addItem(cancelGuiItem);
            navigationPane.addItem(centralItem);
            navigationPane.addItem(cancelGuiItem);
            gui.addPane(Slot.fromXY(3, 1), navigationPane);
        }
        gui.setOnClose(event -> {
            if (event.getReason() != InventoryCloseEvent.Reason.OPEN_NEW && event.getReason() != InventoryCloseEvent.Reason.PLUGIN) {
                vaultGUI.removeUUIDTags(pl.getInventory());
                onClose.run();
            }
        });
        gui.show(pl);
    }

    private void setupSellingBackground(ChestGui gui, boolean success) {
        Material borderMaterial = success ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        Material sideMaterial = success ? Material.GREEN_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE;

        // Top and bottom borders
        OutlinePane top = new OutlinePane(9, 1, Pane.Priority.LOWEST);
        OutlinePane bottom = new OutlinePane(9, 1, Pane.Priority.LOWEST);
        top.addItem(new GuiItem(createGlassPane(borderMaterial)));
        bottom.addItem(new GuiItem(createGlassPane(borderMaterial)));
        top.setRepeat(true);
        bottom.setRepeat(true);

        // Side borders
        OutlinePane left = new OutlinePane(1, 1, Pane.Priority.LOWEST);
        OutlinePane right = new OutlinePane(1, 1, Pane.Priority.LOWEST);
        left.addItem(new GuiItem(createGlassPane(sideMaterial)));
        right.addItem(new GuiItem(createGlassPane(sideMaterial)));

        // Fill middle
        OutlinePane middle = new OutlinePane(1, 1, Pane.Priority.LOWEST);
        middle.addItem(new GuiItem(createGlassPane(Material.GRAY_STAINED_GLASS_PANE)));

        OutlinePane middleRight = new OutlinePane(1, 1, Pane.Priority.LOWEST);
        middleRight.addItem(new GuiItem(createGlassPane(Material.GRAY_STAINED_GLASS_PANE)));

        gui.addPane(Slot.fromXY(0, 0), top);
        gui.addPane(Slot.fromXY(0, 2), bottom);
        gui.addPane(Slot.fromXY(0, 1), left);
        gui.addPane(Slot.fromXY(8, 1), right);
        gui.addPane(Slot.fromXY(1, 1), middle);
        gui.addPane(Slot.fromXY(7, 1), middleRight);
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
