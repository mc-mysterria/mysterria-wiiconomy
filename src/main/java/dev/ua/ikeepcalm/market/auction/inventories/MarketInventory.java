package dev.ua.ikeepcalm.market.auction.inventories;

import de.themoep.inventorygui.InventoryGui;
import de.themoep.inventorygui.StaticGuiElement;
import dev.ua.ikeepcalm.market.util.ItemStackUtil;
import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class MarketInventory {
    private final String[] setup = new String[] { "         ", "  a b c  ", "         " };

    public void open(Player player) {
        InventoryGui gui = new InventoryGui(WIIC.INSTANCE, "Маркет", this.setup);
            gui.addElement(new StaticGuiElement('a', ItemStackUtil.createStack(Material.WRITABLE_BOOK, false, "&a&lІСТОРІЯ", "&fНатисніть, щоб побачити історію покупок"), click -> {
            (new TransactionsInventoryManager()).open(player);
            return true;
        }));
        gui.addElement(new StaticGuiElement('b', ItemStackUtil.createStack(Material.CHEST, false, "&f&lВАШІ ПРЕДМЕТИ", "&fНатисніть, щоб відкрити!"), click -> {
            (new MyItemsInventoryManager()).open(player);
            return true;
        }));
        gui.addElement(new StaticGuiElement('c', ItemStackUtil.createStack(Material.ANVIL, false, "&8&lАУКЦІОН", "&fНатисніть, щоб відкрити!"), click -> {
            (new SalesInventoryManager()).open(player, null, false, null);
            return true;
        }));
        gui.setCloseAction(close -> false);
        gui.show(player);
    }
}
