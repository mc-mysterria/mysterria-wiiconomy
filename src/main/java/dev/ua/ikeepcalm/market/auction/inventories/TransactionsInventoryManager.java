/*
 * Decompiled with CFR 0.150.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.plugin.java.JavaPlugin
 */
package dev.ua.ikeepcalm.market.auction.inventories;

import de.themoep.inventorygui.*;
import dev.ua.ikeepcalm.market.util.AuctionData;
import dev.ua.ikeepcalm.market.util.ItemStackUtil;
import dev.ua.ikeepcalm.market.util.TimeStampUtil;
import dev.ua.ikeepcalm.market.util.Translator;
import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

public class TransactionsInventoryManager {
    private final String[] setup = new String[]{"aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "aaaaaaaaa", "b  ptn   "};

    public void open(Player player) {
        InventoryGui inventoryGui = new InventoryGui(WIIC.INSTANCE, "Історія", this.setup);
        GuiElementGroup group = new GuiElementGroup('a');
        Map<UUID, AuctionData> playerTransactions = WIIC.INSTANCE.getAuctionUtil().getPlayerTransactions(player.getName());
        inventoryGui.addElement(group);

        for (AuctionData object : playerTransactions.values()) {
            if (System.currentTimeMillis() >= object.getTimeStamp() + 1209600000L || object.getBuyer() == null) continue;
            group.addElement(new StaticGuiElement('a', ItemStackUtil.createStack(object.getItem().clone(), "", "&#f2e40c&l\u25cf &fПродавець: &#f2e40c" + object.getSeller(), "&#f2e40c&l\u25cf &fЧас: &#f2e40c" + TimeStampUtil.formatTimestamp(object.getTimeStamp()), "&#f2e40c&l\u25cf &fЦіна: &#f2e40c" + object.getFormattedPrice(), "&#f2e40c&l\u25cf &fПокупець: &#f2e40c" + object.getBuyer())));
        }

        inventoryGui.addElement(new DynamicGuiElement('p', (viewer) -> new StaticGuiElement('p', new ItemStack(Material.ARROW),
                click -> {
                    if (inventoryGui.getPageNumber(player) != 0){
                        inventoryGui.setPageNumber(player, inventoryGui.getPageNumber(player) - 1);
                        click.getGui().draw();
                    }
                    return true;
                },
                Translator.translate("&#f10203&lНАЗАД"), "&f(" + inventoryGui.getPageNumber(player) + "/" + inventoryGui.getPageAmount(player) + ")")));

        inventoryGui.addElement(new DynamicGuiElement('n', (viewer) -> new StaticGuiElement('n', new ItemStack(Material.ARROW),
                click -> {
                    if (inventoryGui.getPageNumber(player)+1 != inventoryGui.getPageAmount(player)) {
                        inventoryGui.setPageNumber(player, inventoryGui.getPageNumber(player) + 1);
                        click.getGui().draw();
                    }
                    return true;
                },
                Translator.translate("&#82f815&lВПЕРЕД"), "&f(" + (inventoryGui.getPageNumber(player) + 1) + "/" + inventoryGui.getPageAmount(player) + ")")));

        inventoryGui.addElement(new StaticGuiElement('b', ItemStackUtil.createStack(Material.BARRIER, false, "&#f10203&lВИЙТИ", "&fНатисніть, щоб повернутися"), click -> {
            new MarketInventory().open(player);
            return true;
        }));
        String[] totalSpentAndMade = WIIC.INSTANCE.getAuctionUtil().getTotalSpentAndMade(playerTransactions, player.getName());
        inventoryGui.addElement(new StaticGuiElement('t', ItemStackUtil.createStack(Material.SPECTRAL_ARROW, true, "&#f2e40c&lІСТОРІЯ", "&#f2e40c&l\u25cf &fПереглядайте всі свої покупки!", "&#f2e40c&l\u25cf &fСумарно зароблено: &#f2e40c" + totalSpentAndMade[1], "&#f2e40c&l\u25cf &fСумарно витрачено: &#f2e40c" + totalSpentAndMade[0])));
        inventoryGui.setCloseAction(close -> false);
        inventoryGui.show(player);
    }
}
