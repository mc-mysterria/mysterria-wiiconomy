package dev.ua.ikeepcalm.market.auction.inventories;

import de.themoep.inventorygui.InventoryGui;
import de.themoep.inventorygui.StaticGuiElement;
import dev.ua.ikeepcalm.market.util.ItemStackUtil;
import dev.ua.ikeepcalm.market.util.ShopItemType;
import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class TypeInventory {
    public void open(Player player) {
        InventoryGui inventoryGui = new InventoryGui(WIIC.INSTANCE, "Оберіть тип", new String[]{"         ", " abcdefg ", "         "});
        inventoryGui.addElement(new StaticGuiElement('a', ItemStackUtil.createStack(Material.DIRT, false, "Блоки"), click -> {
            new SalesInventoryManager().open(player, null, false, ShopItemType.BLOCK);
            return true;
        }));
        inventoryGui.addElement(new StaticGuiElement('b', ItemStackUtil.createStack(Material.IRON_PICKAXE, false, "&3Броня/інструменти"), click -> {
            new SalesInventoryManager().open(player, null, false, ShopItemType.TOOL);
            return true;
        }));
        inventoryGui.addElement(new StaticGuiElement('c', ItemStackUtil.createStack(Material.ENCHANTED_BOOK, false, "Зачаровані книги"), click -> {
            new SalesInventoryManager().open(player, null, false, ShopItemType.ENCHANTED_BOOK);
            return true;
        }));
        inventoryGui.addElement(new StaticGuiElement('d', ItemStackUtil.createStack(Material.WRITTEN_BOOK, false, "Містичні рецепти"), click -> {
            new SalesInventoryManager().open(player, null, false, ShopItemType.MAGIC_RECIPE);
            return true;
        }));
        inventoryGui.addElement(new StaticGuiElement('e', ItemStackUtil.createStack(Material.PLAYER_HEAD, false, "&9Позамежні характеристики"), click -> {
            new SalesInventoryManager().open(player, null, false, ShopItemType.MAGIC_CHARACTERISTIC);
            return true;
        }));
        inventoryGui.addElement(new StaticGuiElement('f', ItemStackUtil.createStack(Material.POTION, false, "&5Магічні зілля"), click -> {
            new SalesInventoryManager().open(player, null, false, ShopItemType.MAGIC_POTION);
            return true;
        }));
        inventoryGui.addElement(new StaticGuiElement('g', ItemStackUtil.createStack(Material.COBWEB, false, "&7Інше"), click -> {
            new SalesInventoryManager().open(player, null, false, ShopItemType.OTHER);
            return true;
        }));
        inventoryGui.show(player);
    }
}
