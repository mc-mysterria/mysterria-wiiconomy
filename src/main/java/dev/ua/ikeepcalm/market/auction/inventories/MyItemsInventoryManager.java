/*
 * Decompiled with CFR 0.150.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.java.JavaPlugin
 *  org.bukkit.scheduler.BukkitTask
 */
package dev.ua.ikeepcalm.market.auction.inventories;

import de.themoep.inventorygui.DynamicGuiElement;
import de.themoep.inventorygui.GuiElementGroup;
import de.themoep.inventorygui.InventoryGui;
import de.themoep.inventorygui.StaticGuiElement;
import dev.ua.ikeepcalm.market.util.*;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public class MyItemsInventoryManager {
    private final String[] setup = new String[]{"aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "b   t    "};

    public void open(Player player) {
        InventoryGui inventoryGui = new InventoryGui(WIIC.INSTANCE, "Ваші предмети", this.setup);
        GuiElementGroup group = new GuiElementGroup('a');
        AuctionUtil auctionUtil = WIIC.INSTANCE.getAuctionUtil();
        auctionUtil.getPlayerSellingItems(player.getName()).thenAccept(uuidAuctionDataMap -> {
            for (AuctionData auctionData : uuidAuctionDataMap.values()) {
                if (auctionData.getBuyer() != null) continue;
                group.addElement(new DynamicGuiElement('a', viewer -> new StaticGuiElement('a', ItemStackUtil.createStack(auctionData.getItem().clone(), "", "&#f2e40c&l\u25cf &fПродавець: &#f2e40c" + auctionData.getSeller(), "&#f2e40c&l\u25cf &fЧас: &#f2e40c" + TimeStampUtil.getCountdown(auctionData.getTimeStamp(), 86400L), "&#f2e40c&l\u25cf &fЦіна: &#f2e40c" + auctionData.getFormattedPrice()), click -> {
                    if (auctionUtil.getItemDataCache().getIfPresent(auctionData.getId()) != null) {
                        ItemStackUtil.giveOrDrop(player, auctionData.getItem().clone());
                        auctionUtil.removeAuctionData(auctionData.getId(), this, player);
                    }
                    return true;
                })));
            }
            inventoryGui.addElement(group);
            inventoryGui.addElement(new StaticGuiElement('b', ItemStackUtil.createStack(Material.BARRIER, false, "&#f10203&lВИЙТИ", "&fНатисніть, щоб повернутися"), click -> {
                new MarketInventory().open(player);
                return true;
            }));
            inventoryGui.addElement(new StaticGuiElement('t', ItemStackUtil.createStack(Material.EMERALD_BLOCK, false, "&aВзяти гроші", "&fЗняти кошти, отримані з продажів"), click -> {
                new InteractiveSlotInventory("Покладіть гаманець", event -> {
                    if (event.item() == null) {
                        inventoryGui.show(player);
                        return;
                    }
                    if (!WalletUtil.isWallet(event.item())) {
                        ItemStackUtil.giveOrDrop(player, event.item());
                        inventoryGui.show(player);
                        return;
                    }
                    String walletId = WalletUtil.getWalletId(event.item());
                    if (walletId == null) {
                        ItemStackUtil.giveOrDrop(player, event.item());
                        inventoryGui.show(player);
                        return;
                    }
                    WalletManager walletManager = new WalletManager();
                    WalletData walletData = walletManager.getWallet(walletId);
                    if (walletData == null) {
                        ItemStackUtil.giveOrDrop(player, event.item());
                        inventoryGui.show(player);
                        return;
                    }
                    PendingMoneyManager pmm = WIIC.getPendingMoneyManager();
                    walletData.setTotalCoppets(walletData.getTotalCoppets() + pmm.getPendingMoney(player.getUniqueId()));
                    walletManager.updateWallet(walletData);
                    pmm.setPendingMoney(player.getUniqueId(), 0);
                    player.sendMessage(Translator.translate("&aВи успішно зняли кошти"));
                    ItemStackUtil.giveOrDrop(player, event.item());
                }).open(player);
                return true;
            }));
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(WIIC.INSTANCE, () -> inventoryGui.draw(), 0L, 20L);
            inventoryGui.setCloseAction(close -> {
                task.cancel();
                return false;
            });
            inventoryGui.show(player);
        });
    }
}

