package dev.ua.ikeepcalm.market.auction.inventories;

import de.themoep.inventorygui.GuiStorageElement;
import de.themoep.inventorygui.InventoryGui;
import de.themoep.inventorygui.StaticGuiElement;
import dev.ua.ikeepcalm.market.auction.events.InteractiveSlotEvent;
import dev.ua.ikeepcalm.market.util.ItemStackUtil;
import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

public class InteractiveSlotInventory {
    String title;
    ItemStack item;
    Consumer<InteractiveSlotEvent> callback;

    public InteractiveSlotInventory(String title, ItemStack item, Consumer<InteractiveSlotEvent> callback) {
        this.title = title;
        this.item = item;
        this.callback = callback;
    }

    public InteractiveSlotInventory(String title, Consumer<InteractiveSlotEvent> callback) {
        this(title, null, callback);
    }

    public void open(Player player) {
        InventoryGui gui = new InventoryGui(WIIC.INSTANCE, title, new String[]{"aia", "aba", "aca"});
        ItemStack filler = ItemStackUtil.createStack(Material.BLACK_STAINED_GLASS_PANE, false);
        gui.addElement(new StaticGuiElement('i', item != null ? item : filler));
        gui.addElement(new StaticGuiElement('a', filler));
        Inventory inventory = Bukkit.createInventory(null, 9);
        gui.addElement(new GuiStorageElement('b', inventory));
        gui.addElement(new StaticGuiElement('c', ItemStackUtil.createStack(Material.GREEN_STAINED_GLASS_PANE, false, "Підтвердити"), click -> {
            gui.close();
            callback.accept(new InteractiveSlotEvent(player, inventory.getItem(0)));
            return true;
        }));
        gui.setCloseAction(close -> {
            callback.accept(new InteractiveSlotEvent(player, inventory.getItem(0)));
            return false;
        });
        gui.show(player);
    }
}
