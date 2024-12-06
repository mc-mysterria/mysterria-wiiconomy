/*
 * Decompiled with CFR 0.150.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.inventory.InventoryCloseEvent
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.InventoryHolder
 *  org.bukkit.inventory.ItemStack
 */
package dev.ua.ikeepcalm.market.auction.inventories;

import de.themoep.inventorygui.InventoryGui;
import dev.ua.ikeepcalm.market.util.AuctionData;
import dev.ua.ikeepcalm.market.util.ItemStackUtil;
import dev.ua.ikeepcalm.market.util.chat.ChatInputAPI;
import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class SellInventory {
    private final InventoryGui previousGui;

    public SellInventory(InventoryGui previousGui) {
        this.previousGui = previousGui;
    }

    public void open(Player player) {
        new InteractiveSlotInventory("Продаж", event -> {
            if (event.item() == null) {
                previousGui.show(player);
                return;
            }
            new ChatInputAPI(
                player,
                Component.text("Введіть в чат ціну предмета у форматі: <аури> <ліки> <копійки>", NamedTextColor.GREEN),
                chatEvent -> sell(player, event.item(), chatEvent.message()),
                cancelEvent -> ItemStackUtil.giveOrDrop(player, event.item())
            ).listen();
        }).open(player);
    }

    private void sell(Player player, ItemStack item, String cost) {
        int price = parsePrice(cost);
        if (price <= 0) {
            player.sendMessage(Component.text("Ціна вказана неправильно!", NamedTextColor.RED));
            ItemStackUtil.giveOrDrop(player, item);
            return;
        }
        AuctionData auctionData = new AuctionData(UUID.randomUUID(), null, System.currentTimeMillis(), price, player.getName(), item.clone());
        WIIC.INSTANCE.getAuctionUtil().saveAuctionData(auctionData).thenAccept(_void -> new SalesInventoryManager().open(player, null, false, null));
    }

    private static int parsePrice(String price) {
        String[] parts = price.split(" ");
        if (parts.length != 3) {
            return -1;
        }
        int[] parsedPartsReversed = new int[3];
        try {
            parsedPartsReversed[2] = Integer.parseInt(parts[0]);
            parsedPartsReversed[1] = Integer.parseInt(parts[1]);
            parsedPartsReversed[0] = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return -1;
        }
        int multiplier = 1;
        int result = 0;
        for (int coins : parsedPartsReversed) {
            result += coins * multiplier;
            multiplier *= 64;
        }
        return result;
    }
}

