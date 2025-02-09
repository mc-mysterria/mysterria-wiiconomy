/*
 * Decompiled with CFR 0.150.
 *
 * Could not load the following classes:
 *  net.md_5.bungee.api.ChatMessageType
 *  net.md_5.bungee.api.chat.TextComponent
 *  org.bukkit.Bukkit
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.inventory.ClickType
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
import dev.ua.ikeepcalm.market.util.chat.ChatInputAPI;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import dev.ua.ikeepcalm.wiic.utils.WalletUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class SalesInventoryManager {
    public void open(Player player, String filter, boolean recentlyListed, ShopItemType typeFilter) {
        InventoryGui inventoryGui = new InventoryGui(WIIC.INSTANCE, "Аукціон", new String[]{"aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "b  pfn  c"});
        Bukkit.getScheduler().runTask(WIIC.INSTANCE, worker -> inventoryGui.show(player));
        AuctionUtil auctionUtil = WIIC.INSTANCE.getAuctionUtil();
        List<AuctionData> dataCollection = new ArrayList<>(filter == null ? auctionUtil.getItemDataCache().asMap().values().stream().toList() : auctionUtil.getFilteredAuctionItems(filter).join().values().stream().toList());
        if (typeFilter != null) {
            dataCollection = new ArrayList<>(dataCollection.stream().filter(data -> ShopItemType.getType(data.getItem()) == typeFilter).toList());
        }
        GuiElementGroup group = new GuiElementGroup('a');
        if (recentlyListed) {
            Comparator<AuctionData> timestampComparator = Comparator.comparing(AuctionData::getTimeStamp);
            dataCollection.sort(timestampComparator.reversed());
        }
        for (AuctionData auctionData : dataCollection) {
            if (auctionData.getBuyer() != null || System.currentTimeMillis() - auctionData.getTimeStamp() >= 86400000L)
                continue;
            group.addElement(new DynamicGuiElement('a', viewer -> new StaticGuiElement('a', ItemStackUtil.createStack(auctionData.getItem().clone(), "", "&#f2e40c&l\u25cf &fПродавець: &#f2e40c" + auctionData.getSeller(), "&#f2e40c&l\u25cf &fЧас: &#f2e40c" + TimeStampUtil.getCountdown(auctionData.getTimeStamp(), 86400L), "&#f2e40c&l\u25cf &fЦіна: &#f2e40c" + auctionData.getFormattedPrice()), click -> {
                new InteractiveSlotInventory("Покладіть гаманець", auctionData.getItem(), event -> {
                    if (event.item() == null) {
                        open(player, filter, recentlyListed, typeFilter);
                        return;
                    }
                    if (!WalletUtil.isWallet(event.item())) {
                        ItemStackUtil.giveOrDrop(player, event.item());
                        open(player, filter, recentlyListed, typeFilter);
                        return;
                    }

                    double price = auctionData.getPrice();
                    double balance = VaultUtil.getBalance(player.getUniqueId());
                    if (balance < price) {
                        ItemStackUtil.giveOrDrop(player, event.item());
                        player.sendMessage(Component.text("Недостатньо коштів!", NamedTextColor.RED));
                        return;
                    }

                    VaultUtil.withdraw(player.getUniqueId(), auctionData.getPrice());
                    auctionUtil.addBuyer(auctionData.getId(), player.getName());
                    player.sendMessage(Translator.translate("&aВи купили " + auctionData.getItem().getType().name() + " за " + auctionData.getFormattedPrice()));
                    UUID sellerId = Bukkit.getOfflinePlayer(auctionData.getSeller()).getUniqueId();
                    PendingMoneyManager pmm = WIIC.getPendingMoneyManager();
                    pmm.setPendingMoney(sellerId, pmm.getPendingMoney(sellerId) + auctionData.getPrice());
                    Player seller = Bukkit.getPlayer(auctionData.getSeller());
                    if (seller != null) {
                        seller.sendMessage(Translator.translate("&aВаш " + auctionData.getItem().getType().name() + " був проданий за " + auctionData.getFormattedPrice()));
                    }
                    ItemStackUtil.giveOrDrop(player, event.item());
                    ItemStackUtil.giveOrDrop(player, auctionData.getItem().clone());
                }).open(player);
                return true;
            })));
        }

        inventoryGui.addElement(group);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(WIIC.INSTANCE, () -> inventoryGui.draw(), 0L, 20L);
        inventoryGui.addElement(new StaticGuiElement('b', ItemStackUtil.createStack(Material.BARRIER, false, "&#f10203&lВИЙТИ", "&fНатисніть, щоб повернутися"), click -> {
            new MarketInventory().open(player);
            return true;
        }));

        inventoryGui.addElement(new DynamicGuiElement('p', (viewer) -> new StaticGuiElement('p', new ItemStack(Material.ARROW),
                click -> {
                    if (inventoryGui.getPageNumber(player) != 0) {
                        inventoryGui.setPageNumber(player, inventoryGui.getPageNumber(player) - 1);
                        click.getGui().draw();
                    }
                    return true;
                },
                Translator.translate("&#f10203&lНАЗАД"), "&f(" + inventoryGui.getPageNumber(player) + "/" + inventoryGui.getPageAmount(player) + ")")));

        inventoryGui.addElement(new DynamicGuiElement('n', (viewer) -> new StaticGuiElement('n', new ItemStack(Material.ARROW),
                click -> {
                    if (inventoryGui.getPageNumber(player) + 1 != inventoryGui.getPageAmount(player)) {
                        inventoryGui.setPageNumber(player, inventoryGui.getPageNumber(player) + 1);
                        click.getGui().draw();
                    }
                    return true;
                },
                Translator.translate("&#82f815&lВПЕРЕД"), "&f(" + (inventoryGui.getPageNumber(player) + 1) + "/" + inventoryGui.getPageAmount(player) + ")")));

        inventoryGui.addElement(new StaticGuiElement('f', ItemStackUtil.createStack(Material.SPECTRAL_ARROW, true, "&#f2e40c&lАУКЦІОН", "&#f2e40c&l\u25cf &fНатисніть, щоб продати предмет"), click -> {
            CompletableFuture<Map<UUID, AuctionData>> itemsOnSale = auctionUtil.getPlayerSellingItems(player.getName());
            int onSale;
            try {
                onSale = itemsOnSale.get().size();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
            User user = WIIC.getLuckPerms().getPlayerAdapter(Player.class).getUser(player);
            int slotLimit = getSlotLimit(user);

            if (slotLimit <= onSale) {
                player.closeInventory();
                player.sendMessage(Component.text("На жаль, у Вас немає вільних слотів для аукціону!", NamedTextColor.RED));
            } else {
                new SellInventory(inventoryGui).open(player);
            }
            return true;
        }));
        inventoryGui.addElement(new StaticGuiElement('c', ItemStackUtil.createStack(Material.SPRUCE_SIGN, false, "&#f2e40c&lПОШУК", "&#f2e40c&l\u25cf &fЛКМ, щоб шукати по назві", "&#f2e40c&l\u25cf &fПКМ, щоб фільтрувати за типом"), click -> {
            if (click.getType() == ClickType.LEFT) {
                this.openFilterInput(player, typeFilter);
            }
            if (click.getType() == ClickType.RIGHT) {
                new TypeInventory().open(player);
            }
            return true;
        }));
        inventoryGui.setCloseAction(close -> {
            task.cancel();
            return false;
        });
        inventoryGui.draw(player);
    }

    public void openFilterInput(Player target, ShopItemType typeFilter) {
        target.closeInventory();
        new ChatInputAPI(
                target,
                Component.text("Введіть текст для пошуку в чат", NamedTextColor.GREEN),
                event -> this.open(target, event.message(), false, typeFilter),
                event -> {
                }
        ).listen();
    }

    private static int getSlotLimit(User user) {
        String group = user.getPrimaryGroup();
        int slotLimit = 0;
        Configuration configuration = WIIC.INSTANCE.getConfig();
        ConfigurationSection groupsSection = configuration.getConfigurationSection("market.groups");

        if (groupsSection != null) {
            for (String groupName : groupsSection.getKeys(false)) {
                int groupValue = groupsSection.getInt(groupName);
                if (group.equals(groupName)) {
                    slotLimit = groupValue;
                }
            }
        }
        return slotLimit;
    }
}

