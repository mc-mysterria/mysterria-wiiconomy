package dev.ua.ikeepcalm.wiic.gui;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.currency.services.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WalletGUI {
    ChestGui gui;
    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;
    private boolean callOnClose = true;
    public final static Set<Player> playersWithOpenWallets = new HashSet<>();

    public WalletGUI(PriceAppraiser priceAppraiser, SoldItemsManager soldItemsManager) {
        this.priceAppraiser = priceAppraiser;
        this.soldItemsManager = soldItemsManager;
    }

    public void open(Player player, WalletData data, Runnable onClose) {
        gui = new ChestGui(3, ComponentHolder.of(Component.text("\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3201\u3206").color(NamedTextColor.WHITE)));

        final OutlinePane head = new OutlinePane(1, 1, 1,1);
        head.addItem(new GuiItem(getSkull(player), click -> click.setCancelled(true)));
        gui.addPane(head);

        final OutlinePane menu = new OutlinePane(4, 1, 3, 2);
        menu.addItem(new GuiItem(getDonateInfoItem(player), click -> click.setCancelled(true)));
        menu.addItem(new GuiItem(getConverterItem(data), click -> {
            click.setCancelled(true);
            callOnClose = false;
            player.closeInventory();
            new VaultGUI(priceAppraiser, soldItemsManager).openVault(player, onClose);
        }));
        gui.addPane(menu);

        gui.setOnClose(event -> {
            if (callOnClose) {
                onClose.run();
            }
        });

        gui.setOnGlobalClick(click -> click.setCancelled(true));

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
        meta.itemName(Component.translatable("wiic.gui.wallet.donate_currency").color(NamedTextColor.BLUE));
        meta.lore(List.of(Component.translatable("wiic.gui.wallet.current_balance", Component.text(getDonateBalance(player)))
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)));
        meta.setItemModel(new NamespacedKey(WIIC.INSTANCE, "donate"));
        item.setItemMeta(meta);
        return item;
    }

    private static String getDonateBalance(Player player) {
        // Placeholder for future currency implementation
        return "0M";
    }

    private static ItemStack getConverterItem(WalletData data) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.translatable("wiic.gui.wallet.game_currency").color(NamedTextColor.GOLD));
        meta.lore(List.of(Component.translatable("wiic.gui.wallet.current_balance", Component.text(CoinUtil.getFormattedPrice(data.getTotalCoppets())))
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)));
        meta.setItemModel(new NamespacedKey(WIIC.INSTANCE, "game_money"));
        item.setItemMeta(meta);
        return item;
    }
}
