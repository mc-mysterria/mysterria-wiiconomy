package dev.ua.ikeepcalm.wiic.guis;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import dev.ua.ikeepcalm.market.util.AuctionUtil;
import dev.ua.ikeepcalm.wiic.economy.Appraiser;
import dev.ua.ikeepcalm.wiic.economy.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.wallet.WalletManager;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public class WalletGUI {
    ChestGui gui;
    private final Appraiser appraiser;
    private final WalletManager walletManager;
    private final SoldItemsManager soldItemsManager;

    public WalletGUI(Appraiser appraiser, WalletManager walletManager, SoldItemsManager soldItemsManager) {
        this.appraiser = appraiser;
        this.walletManager = walletManager;
        this.soldItemsManager = soldItemsManager;
    }

    public void open(Player player, WalletData data) {
        gui = new ChestGui(3, ComponentHolder.of(Component.text("\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3206").color(NamedTextColor.WHITE)));

        final OutlinePane head = new OutlinePane(1, 1, 1,1);
        head.addItem(new GuiItem(getSkull(player), click -> click.setCancelled(true)));
        gui.addPane(head);

        final OutlinePane menu = new OutlinePane(4, 1, 3, 2);
        menu.addItem(new GuiItem(getDonateInfoItem(player), click -> click.setCancelled(true)));
        menu.addItem(new GuiItem(getConverterItem(data), click -> {
            click.setCancelled(true);
            new VaultGUI(appraiser, walletManager, soldItemsManager).openVault(player, data);
        }));
        gui.addPane(menu);

        gui.show(player);
    }

    private static ItemStack getSkull(Player player) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(player);
        meta.customName(Component.text(player.getName()).decoration(TextDecoration.ITALIC, false));
        skull.setItemMeta(meta);
        return skull;
    }

    private static ItemStack getDonateInfoItem(Player player) {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Донатна валюта").color(NamedTextColor.BLUE));
        meta.lore(List.of(Component.text("Поточний рахунок: " + getDonateBalance(player))
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static int getDonateBalance(Player player) {
        return 0;
    }

    private static ItemStack getConverterItem(WalletData data) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Ігрова валюта").color(NamedTextColor.GOLD));
        meta.lore(List.of(Component.text("Поточний рахунок: " + AuctionUtil.getFormattedPrice(data.getTotalCoppets()))
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }
}
