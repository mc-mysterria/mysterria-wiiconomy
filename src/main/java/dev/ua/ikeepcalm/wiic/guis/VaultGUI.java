package dev.ua.ikeepcalm.wiic.guis;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.economy.Appraiser;
import dev.ua.ikeepcalm.wiic.economy.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    ChestGui gui;
    OutlinePane walletInventory;
    OutlinePane playerInventory;

    private boolean actionClose = false;
    private final Appraiser appraiser;
    private final SoldItemsManager soldItemsManager;

    public VaultGUI(Appraiser appraiser, SoldItemsManager soldItemsManager) {
        this.appraiser = appraiser;
        this.soldItemsManager = soldItemsManager;
    }

    public void openVault(Player player, Runnable onClose) {
        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> openVaultSync(player, onClose));
    }

    public void openVaultSync(Player player, Runnable onClose) {
        WalletData data = VaultUtil.getWalletData(player.getUniqueId()).join();
        gui = new ChestGui(4, ComponentHolder.of(Component.text("Зняття/поповнення").color(NamedTextColor.DARK_GREEN)));
        setupBackground();
        walletInventory = new OutlinePane(1, 1, 7, 3, Pane.Priority.HIGH);
        playerInventory = new OutlinePane(1, 4, 7, 3, Pane.Priority.HIGH);

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
            if (appraiser.appraise(item) > 0) {
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

        for (ItemStack iteItem : itemsInWallet) {
            walletInventory.addItem(new GuiItem(iteItem, event -> {
                ItemStack item = event.getCurrentItem();
                actionClose = true;
                new ActionGUI(player, item, this, new ActionGUI.ConfirmationCallback() {
                    @Override
                    public void onConfirm(ItemStack item) {
                        if (player.getInventory().firstEmpty() == -1) {
                            player.sendMessage(Component.text("Ваш інвентар повний!").color(NamedTextColor.RED));
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
        }

        for (ItemStack iteItem : itemsForSale) {
            playerInventory.addItem(new GuiItem(iteItem, event -> {
                if (player.getInventory().getItemInOffHand().getType() != Material.AIR) {
                    player.sendMessage(Component.text("Ви повинні мати порожню руку!").color(NamedTextColor.RED));
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
                }, appraiser, soldItemsManager, onClose).open();
            }));
        }

        for (ItemStack iteItem : itemsInInventory) {
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
        OutlinePane top = new OutlinePane(0, 0, 9, 1, Pane.Priority.LOWEST);
        OutlinePane bottom = new OutlinePane(0, 7, 9, 1, Pane.Priority.LOWEST);
        OutlinePane left = new OutlinePane(0, 1, 1, 7, Pane.Priority.LOWEST);
        OutlinePane right = new OutlinePane(8, 1, 1, 7, Pane.Priority.LOWEST);
        top.addItem(new GuiItem(new ItemStack(Material.BLACK_STAINED_GLASS_PANE)));
        bottom.addItem(new GuiItem(new ItemStack(Material.BLACK_STAINED_GLASS_PANE)));
        left.addItem(new GuiItem(new ItemStack(Material.BLACK_STAINED_GLASS_PANE)));
        right.addItem(new GuiItem(new ItemStack(Material.BLACK_STAINED_GLASS_PANE)));
        top.setRepeat(true);
        bottom.setRepeat(true);
        left.setRepeat(true);
        right.setRepeat(true);
        gui.addPane(top);
        gui.addPane(bottom);
        gui.addPane(left);
        gui.addPane(right);
    }


    private void handleTransferredItem(Player player, ItemStack newItem) {
        switch (ItemUtil.getType(newItem)) {
            case "verlDor":
                VaultUtil.deposit(player.getUniqueId(), newItem.getAmount() * 64 * 64);
                break;
            case "lick":
                VaultUtil.deposit(player.getUniqueId(), newItem.getAmount() * 64);
                break;
            case "coppet":
                VaultUtil.deposit(player.getUniqueId(), newItem.getAmount());
                break;
        }
    }

    private void handleSoldItem(Player player, ItemStack newItem) {
        int appraisal = appraiser.appraise(newItem);
        VaultUtil.deposit(player.getUniqueId(), appraisal);
        soldItemsManager.setSoldValue(player, soldItemsManager.getSoldValue(player) + appraisal);
    }

    private void handleWithdrawnItem(Player player, ItemStack newItem) {
        switch (ItemUtil.getType(newItem)) {
            case "verlDor":
                VaultUtil.withdraw(player.getUniqueId(), newItem.getAmount() * 64 * 64);
                break;
            case "lick":
                VaultUtil.withdraw(player.getUniqueId(), newItem.getAmount() * 64);
                break;
            case "coppet":
                VaultUtil.withdraw(player.getUniqueId(), newItem.getAmount());
                break;
        }
    }
}