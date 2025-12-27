package dev.ua.ikeepcalm.wiic.gui;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import dev.ua.ikeepcalm.wiic.currency.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.currency.services.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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

    public final static Set<Player> playersWithOpenWallets = new HashSet<>();
    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;

    private boolean callOnClose = true;

    public WalletGUI(PriceAppraiser priceAppraiser, SoldItemsManager soldItemsManager) {
        this.priceAppraiser = priceAppraiser;
        this.soldItemsManager = soldItemsManager;
    }

    private static ItemStack getSkull(Player player) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(player);

        meta.customName(Component.text(player.getName())
                .color(TextColor.color(0xFFD700))
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));

        meta.lore(List.of(
                Component.empty(),
                Component.text("⚜ ").color(TextColor.color(0xFFD700))
                        .append(Component.translatable("wiic.gui.wallet.profile_info")
                                .color(NamedTextColor.GRAY))
                        .append(Component.text(" ⚜").color(TextColor.color(0xFFD700)))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty()
        ));

        skull.setItemMeta(meta);
        return skull;
    }

    private static ItemStack getDonateInfoItem(Player player) {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();

        meta.itemName(Component.translatable("wiic.gui.wallet.donate_currency")
                .color(TextColor.color(0x00BFFF))
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));

        String balance = getDonateBalance(player);
        meta.lore(List.of(
                Component.empty(),
                Component.text("━━━━━━━━━━━━━━━━━━━━")
                        .color(TextColor.color(0x4682B4))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  ◆ ").color(TextColor.color(0x87CEEB))
                        .append(Component.translatable("wiic.gui.wallet.current_balance", Component.text(balance)
                                        .color(TextColor.color(0x00FF7F))
                                        .decoration(TextDecoration.BOLD, true))
                                .color(NamedTextColor.AQUA))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  ℹ ").color(NamedTextColor.GRAY)
                        .append(Component.translatable("wiic.gui.wallet.donate_description")
                                .color(NamedTextColor.DARK_GRAY))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("━━━━━━━━━━━━━━━━━━━━")
                        .color(TextColor.color(0x4682B4))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty()
        ));

        meta.setItemModel(new NamespacedKey(WIIC.INSTANCE, "donate"));
        item.setItemMeta(meta);
        return item;
    }

    private static String getDonateBalance(Player player) {
        return "0M";
    }

    private static ItemStack getConverterItem(WalletData data) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();

        meta.itemName(Component.translatable("wiic.gui.wallet.game_currency")
                .color(TextColor.color(0xFFD700))
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));

        Component formattedBalance = CoinUtil.getFormattedPrice(data.getTotalCoppets());
        meta.lore(List.of(
                Component.empty(),
                Component.text("━━━━━━━━━━━━━━━━━━━━")
                        .color(TextColor.color(0xDAA520))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  ◆ ").color(TextColor.color(0xFFD700))
                        .append(Component.translatable("wiic.gui.wallet.current_balance").append(formattedBalance)
                                .color(TextColor.color(0x00FF00))
                                .decoration(TextDecoration.BOLD, true))
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  ➤ ").color(NamedTextColor.GOLD)
                        .append(Component.translatable("wiic.gui.wallet.click_to_convert")
                                .color(NamedTextColor.GRAY))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  ℹ ").color(NamedTextColor.GRAY)
                        .append(Component.translatable("wiic.gui.wallet.currency_info")
                                .color(NamedTextColor.DARK_GRAY))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("━━━━━━━━━━━━━━━━━━━━")
                        .color(TextColor.color(0xDAA520))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty()
        ));

        meta.setItemModel(new NamespacedKey(WIIC.INSTANCE, "game_money"));
        item.setItemMeta(meta);
        return item;
    }

    public void open(Player player, WalletData data, Runnable onClose) {
        ChestGui gui = new ChestGui(3, ComponentHolder.of(Component.text("㈁㈁㈁㈁㈁㈁㈁㈁㈁㈁㈁㈁㈆").color(NamedTextColor.WHITE)));

        final OutlinePane head = new OutlinePane(1, 1, 1, 1);
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
}
