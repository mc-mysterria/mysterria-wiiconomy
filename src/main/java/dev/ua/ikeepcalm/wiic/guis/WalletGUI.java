package dev.ua.ikeepcalm.wiic.guis;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import de.tr7zw.nbtapi.NBTItem;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.economy.Appraiser;
import dev.ua.ikeepcalm.wiic.economy.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

public class WalletGUI {

    ChestGui gui;
    OutlinePane walletInventory;
    OutlinePane playerInventory;

    private boolean actionClose = false;
    private final Appraiser appraiser;
    private final WalletManager walletManager;
    private final SoldItemsManager soldItemsManager;

    public WalletGUI(Appraiser appraiser, WalletManager walletManager, SoldItemsManager soldItemsManager) {
        this.appraiser = appraiser;
        this.walletManager = walletManager;
        this.soldItemsManager = soldItemsManager;
    }


    public void openVault(Player player, WalletData data) {
        gui = new ChestGui(4, ComponentHolder.of(Component.text("Гаманець").color(NamedTextColor.DARK_GREEN)));
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
            if (item == null || item.getType() == Material.AIR) continue;
            if (item.getType() == Material.GOLD_INGOT && item.hasItemMeta()) {
                NBTItem nbtItem = new NBTItem(item);
                if (nbtItem.hasKey("type")) {
                    itemsInInventory.add(item);
                }
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
                        handleWithdrawnItem(item, data);
                        gui.update();
                        openVault(player, data);
                    }

                    @Override
                    public void onCancel() {
                        gui.update();
                        openVault(player, data);
                    }
                }).open();
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
                        handleSoldItem(player, item, data);
                        openVault(player, data);
                    }

                    @Override
                    public void onCancel() {
                        gui.update();
                        openVault(player, data);
                    }
                }, appraiser, soldItemsManager).open();
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
                        updateWalletData(data);
                        gui.update();
                        openVault(player, data);
                    }

                    @Override
                    public void onCancel() {
                        gui.update();
                        openVault(player, data);
                    }
                }).open();
            });
            playerInventory.addItem(guiItem);
        }

        gui.addPane(walletInventory);
        gui.addPane(playerInventory);

        gui.setOnGlobalClick(event -> event.setCancelled(true));

        gui.setOnClose(event -> {
            updateWalletData(data);
            walletManager.updateWallet(data);
            if (!actionClose) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        removeUUIDTags(player.getInventory());
                    }
                }.runTaskLaterAsynchronously(WIIC.INSTANCE, 20);
            } else {
                actionClose = false;
            }
        });
        gui.show(player);
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

    private void handleSoldItem(Player player, ItemStack newItem, WalletData data) {
        int appraisal = appraiser.appraise(newItem);
        data.setCoppets(data.getCoppets() + appraisal);
        soldItemsManager.setSoldValue(player, soldItemsManager.getSoldValue(player) + appraisal);
    }

    private void handleWithdrawnItem(ItemStack newItem, WalletData data) {
        NBTItem nbtItem = new NBTItem(newItem);
        switch (nbtItem.getString("type")) {
            case "verlDor":
                data.setVerlDors(data.getVerlDors() - newItem.getAmount());
                break;
            case "lick":
                data.setLicks(data.getLicks() - newItem.getAmount());
                break;
            case "coppet":
                data.setCoppets(data.getCoppets() - newItem.getAmount());
                break;
        }
    }

    private void updateWalletData(WalletData data) {
        int verlDors = 0;
        int licks = 0;
        int coppets = 0;

        for (GuiItem item : walletInventory.getItems()) {
            if (item.getItem().getType() == Material.AIR) continue;
            NBTItem nbtItem = new NBTItem(item.getItem());
            switch (nbtItem.getString("type")) {
                case "verlDor":
                    verlDors = (verlDors + item.getItem().getAmount());
                    break;
                case "lick":
                    licks = (licks + item.getItem().getAmount());
                    break;
                case "coppet":
                    coppets = (coppets + item.getItem().getAmount());
                    break;
            }
        }

        while (coppets >= 64) {
            coppets -= 64;
            licks++;
        }

        while (licks >= 64) {
            licks -= 64;
            verlDors++;
        }

        data.setVerlDors(verlDors);
        data.setLicks(licks);
        data.setCoppets(coppets);
    }
}