package dev.ua.ikeepcalm.wiic.gui;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import dev.ua.ikeepcalm.wiic.currency.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.currency.services.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.locale.MessageManager;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

public class VaultGUI {

    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;
    ChestGui gui;
    OutlinePane walletInventory;
    OutlinePane playerInventory;
    private boolean actionClose = false;

    public VaultGUI(PriceAppraiser priceAppraiser, SoldItemsManager soldItemsManager) {
        this.priceAppraiser = priceAppraiser;
        this.soldItemsManager = soldItemsManager;
    }

    public void openVault(Player player, Runnable onClose) {
        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> openVaultSync(player, onClose));
    }

    public void openVaultSync(Player player, Runnable onClose) {
        WalletData data = VaultUtil.getWalletData(player.getUniqueId()).join();
        Component title = MessageManager.getMessage("wiic.gui.vault.title");
        gui = new ChestGui(6, ComponentHolder.of(title));
        setupBackground();
        setupSectionHeaders(data);
        walletInventory = new OutlinePane(1, 1, 7, 1, Pane.Priority.HIGH);
        playerInventory = new OutlinePane(1, 4, 7, 1, Pane.Priority.HIGH);

        while (data.getLicks() >= 64) {
            data.setLicks(data.getLicks() - 64);
            data.setVerlDors(data.getVerlDors() + 1);
        }

        while (data.getCoppets() >= 64) {
            data.setCoppets(data.getCoppets() - 64);
            data.setLicks(data.getLicks() + 1);
        }

        List<ItemStack> itemsForSale = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (priceAppraiser.appraise(item) > 0) {
                itemsForSale.add(item);
            }
        }

        List<ItemStack> itemsInInventory = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (CoinUtil.isCoin(item)) {
                itemsInInventory.add(item);
            }
        }

        List<ItemStack> itemsInWallet = new ArrayList<>();
        if (data.getVerlDors() > 0) {
            itemsInWallet.add(CoinUtil.getVerlDor(data.getVerlDors()));
        }

        if (data.getLicks() > 0) {
            itemsInWallet.add(CoinUtil.getLick(data.getLicks()));
        }

        if (data.getCoppets() > 0) {
            itemsInWallet.add(CoinUtil.getCoppet(data.getCoppets()));
        }

        // Limit wallet items to 7 (single row) to prevent overflow
        int walletItemsAdded = 0;
        final int MAX_WALLET_ITEMS = 7;
        for (ItemStack iteItem : itemsInWallet) {
            if (walletItemsAdded >= MAX_WALLET_ITEMS) break;

            walletInventory.addItem(new GuiItem(iteItem, event -> {
                ItemStack item = event.getCurrentItem();
                actionClose = true;
                new ActionGUI(player, item, this, new ActionGUI.ConfirmationCallback() {
                    @Override
                    public void onConfirm(ItemStack item) {
                        if (player.getInventory().firstEmpty() == -1) {
                            player.sendMessage(MessageManager.getMessage("wiic.wallet.error.inventory_full"));
                            return;
                        }
                        player.getInventory().addItem(item);
                        player.closeInventory();
                        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
                            handleWithdrawnItem(player, item);
                            Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> {
                                gui.update();
                                openVault(player, onClose);
                            });
                        });
                    }

                    @Override
                    public void onCancel() {
                        gui.update();
                        openVault(player, onClose);
                    }
                }, onClose).open();
            }));
            walletItemsAdded++;
        }

        // Combine items for sale and coins in inventory, limit to 7 (single row)
        int inventoryItemsAdded = 0;
        final int MAX_INVENTORY_ITEMS = 7;

        // Add coins in inventory first (higher priority)
        for (ItemStack iteItem : itemsInInventory) {
            if (inventoryItemsAdded >= MAX_INVENTORY_ITEMS) break;

            GuiItem guiItem = new GuiItem(iteItem, event -> {
                ItemStack item = event.getCurrentItem();
                actionClose = true;
                new ActionGUI(player, item, this, new ActionGUI.ConfirmationCallback() {
                    @Override
                    public void onConfirm(ItemStack item) {
                        player.getInventory().removeItem(item);
                        walletInventory.addItem(new GuiItem(item));
                        player.closeInventory();
                        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
                            handleTransferredItem(player, item);
                            Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> {
                                gui.update();
                                openVault(player, onClose);
                            });
                        });
                    }

                    @Override
                    public void onCancel() {
                        gui.update();
                        openVault(player, onClose);
                    }
                }, onClose).open();
            });
            playerInventory.addItem(guiItem);
            inventoryItemsAdded++;
        }

        // Add items for sale (secondary priority)
        for (ItemStack iteItem : itemsForSale) {
            if (inventoryItemsAdded >= MAX_INVENTORY_ITEMS) break;

            playerInventory.addItem(new GuiItem(iteItem, event -> {
                if (player.getInventory().getItemInOffHand().getType() != Material.AIR) {
                    player.sendMessage(MessageManager.getMessage("wiic.wallet.error.empty_hand_required"));
                    return;
                }
                ItemStack item = event.getCurrentItem();
                actionClose = true;
                new SellingGUI(player, item, this, new SellingGUI.ConfirmationCallback() {
                    @Override
                    public void onConfirm(ItemStack item) {
                        player.getInventory().removeItem(item);
                        player.closeInventory();
                        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
                            handleSoldItem(player, item);
                            Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> openVault(player, onClose));
                        });
                    }

                    @Override
                    public void onCancel() {
                        gui.update();
                        openVault(player, onClose);
                    }
                }, priceAppraiser, soldItemsManager, onClose).open();
            }));
            inventoryItemsAdded++;
        }

        gui.addPane(walletInventory);
        gui.addPane(playerInventory);

        gui.setOnGlobalClick(event -> event.setCancelled(true));

        gui.setOnClose(event -> {
            //updateWalletData(player, data);
            if (!actionClose) {
                for (int i = 1; i <= 20; i++) {
                    Bukkit.getScheduler().runTaskLaterAsynchronously(WIIC.INSTANCE, () -> removeUUIDTags(player.getInventory()), i);
                }
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        onClose.run();
                    }
                }.runTaskLaterAsynchronously(WIIC.INSTANCE, 20);
            } else {
                actionClose = false;
            }
        });
        Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> gui.show(player));
    }

    public void removeUUIDTags(PlayerInventory inventory) {
        for (ItemStack item : inventory) {
            if (item == null) continue;
            for (GuiItem guiItem : playerInventory.getItems()) {
                ItemMeta meta1 = item.getItemMeta();
                ItemMeta meta2 = guiItem.getItem().getItemMeta();
                if (meta1 != null && meta1.equals(meta2)) {
                    meta1.getPersistentDataContainer().remove(guiItem.getKey());
                    item.setItemMeta(meta1);
                }
            }
        }
    }

    private void setupBackground() {
        // Top border (gold themed)
        OutlinePane top = new OutlinePane(0, 0, 9, 1, Pane.Priority.LOWEST);
        top.addItem(new GuiItem(createGlassPane(Material.YELLOW_STAINED_GLASS_PANE)));
        top.setRepeat(true);

        // Wallet section separator (row 3)
        OutlinePane walletSeparator = new OutlinePane(0, 3, 9, 1, Pane.Priority.LOWEST);
        walletSeparator.addItem(new GuiItem(createGlassPane(Material.ORANGE_STAINED_GLASS_PANE)));
        walletSeparator.setRepeat(true);

        // Bottom border
        OutlinePane bottom = new OutlinePane(0, 5, 9, 1, Pane.Priority.LOWEST);
        bottom.addItem(new GuiItem(createGlassPane(Material.YELLOW_STAINED_GLASS_PANE)));
        bottom.setRepeat(true);

        // Side borders (gray)
        OutlinePane left = new OutlinePane(0, 1, 1, 4, Pane.Priority.LOWEST);
        OutlinePane right = new OutlinePane(8, 1, 1, 4, Pane.Priority.LOWEST);
        left.addItem(new GuiItem(createGlassPane(Material.GRAY_STAINED_GLASS_PANE)));
        right.addItem(new GuiItem(createGlassPane(Material.GRAY_STAINED_GLASS_PANE)));
        left.setRepeat(true);
        right.setRepeat(true);

        gui.addPane(top);
        gui.addPane(walletSeparator);
        gui.addPane(bottom);
        gui.addPane(left);
        gui.addPane(right);
    }

    private void setupSectionHeaders(WalletData data) {
        OutlinePane walletHeader = new OutlinePane(4, 0, 1, 1, Pane.Priority.HIGH);
        walletHeader.addItem(new GuiItem(createHeaderItem(
                Material.GOLD_INGOT,
                Component.translatable("wiic.gui.vault.wallet_section")
                        .color(TextColor.color(0xFFD700))
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(
                        Component.translatable("wiic.gui.vault.separator")
                                .color(TextColor.color(0xDAA520))
                                .decoration(TextDecoration.ITALIC, false),
                        Component.translatable("wiic.gui.vault.total_balance",
                                        CoinUtil.getFormattedPrice(data.getTotalCoppets()))
                                .color(NamedTextColor.GREEN)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.translatable("wiic.gui.vault.wallet_hint")
                                .color(NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                )
        )));

        // Inventory section header (centered at middle separator)
        OutlinePane inventoryHeader = new OutlinePane(4, 3, 1, 1, Pane.Priority.HIGH);
        inventoryHeader.addItem(new GuiItem(createHeaderItem(
                Material.CHEST,
                Component.translatable("wiic.gui.vault.inventory_section")
                        .color(TextColor.color(0x87CEEB))
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(
                        Component.translatable("wiic.gui.vault.separator")
                                .color(TextColor.color(0x4682B4))
                                .decoration(TextDecoration.ITALIC, false),
                        Component.translatable("wiic.gui.vault.inventory_hint")
                                .color(NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                )
        )));

        gui.addPane(walletHeader);
        gui.addPane(inventoryHeader);
    }

    private ItemStack createGlassPane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createHeaderItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void handleTransferredItem(Player player, ItemStack newItem) {
        final String type = ItemUtil.getType(newItem);
        if (type != null) {
            switch (type) {
                case "goldcoin":
                    VaultUtil.deposit(player.getUniqueId(), newItem.getAmount() * 64 * 64);
                    break;
                case "silvercoin":
                    VaultUtil.deposit(player.getUniqueId(), newItem.getAmount() * 64);
                    break;
                case "coppercoin":
                    VaultUtil.deposit(player.getUniqueId(), newItem.getAmount());
                    break;
            }
        }
    }

    private void handleSoldItem(Player player, ItemStack newItem) {
        int appraisal = priceAppraiser.appraise(newItem);
        VaultUtil.deposit(player.getUniqueId(), appraisal);
        soldItemsManager.setSoldValue(player, soldItemsManager.getSoldValue(player) + appraisal);
    }

    private void handleWithdrawnItem(Player player, ItemStack newItem) {
        final String type = ItemUtil.getType(newItem);
        if (type != null) {
            switch (type) {
                case "goldcoin":
                    VaultUtil.withdraw(player.getUniqueId(), newItem.getAmount() * 64 * 64);
                    break;
                case "silvercoin":
                    VaultUtil.withdraw(player.getUniqueId(), newItem.getAmount() * 64);
                    break;
                case "coppercoin":
                    VaultUtil.withdraw(player.getUniqueId(), newItem.getAmount());
                    break;
            }
        }
    }
}