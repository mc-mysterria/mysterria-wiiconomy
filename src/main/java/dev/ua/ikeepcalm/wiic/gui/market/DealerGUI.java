package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.dealer.model.DealerItem;
import dev.ua.ikeepcalm.wiic.domain.agora.dealer.model.source.DealResult;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class DealerGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int INFO_SLOT = 22;
    private static final int PREVIEW_SLOT = 13;
    private static final int CONFIRM_SLOT = 29;
    private static final int CANCEL_SLOT = 33;
    private static final List<String> OFFER_LORE = List.of("", "<gold>%price%", "<gray>ʟᴇꜰᴛ ɪɴ sᴛᴏᴄᴋ: <white>%stock%");
    private static final List<String> SOLD_OUT_LORE = List.of("", "<gray>sᴏʟᴅ ᴏᴜᴛ");

    private final MarketServices services;

    public void open(Player player) {
        if (!services.config().dealerEnabled()) {
            tell(player, DealResult.CLOSED);
            return;
        }
        Map<DealerItem, Integer> offers = services.dealer().offers();
        if (offers.isEmpty()) {
            player.sendMessage(MM.deserialize(services.config().message("dealer-empty",
                    "<gray>\"Nothing worth your coin today. Come back after the next shipment.\"")));
            return;
        }
        ConfigurationSection config = services.config().guiSection("dealer-gui");
        Gui gui = board(player, config, 3);

        List<Integer> slots = offerSlots(offers.size());
        int index = 0;
        for (var offer : offers.entrySet()) {
            DealerItem item = offer.getKey();
            int stock = offer.getValue();
            ItemStack icon = icon(item, config, stock > 0 ? "items.offer.lore" : "items.sold-out.lore",
                    stock > 0 ? OFFER_LORE : SOLD_OUT_LORE, stock);
            gui.setItem(slots.get(index++), Item.builder().setItemProvider(icon)
                    .addClickHandler(_ -> {
                        if (stock > 0) {
                            confirm(player, item);
                        } else {
                            tell(player, DealResult.SOLD_OUT);
                        }
                    })
                    .build());
        }

        int left = services.dealer().purchasesLeft(player.getUniqueId());
        gui.setItem(INFO_SLOT, Item.builder().setItemProvider(MarketBrowseGUI.configItem(config, "items.info", player,
                Material.CLOCK, "<dark_purple>ɴᴇxᴛ sʜɪᴘᴍᴇɴᴛ ɪɴ %next%", Map.of(
                        "%next%", MarketBrowseGUI.formatRemaining(services.dealer().getNextRollAt() - System.currentTimeMillis()),
                        "%left%", left == Integer.MAX_VALUE ? "∞" : String.valueOf(left)))).build());

        show(player, gui, config != null ? config.getString("title", "The Curio Dealer") : "The Curio Dealer", () -> {});
    }

    private void confirm(Player player, DealerItem item) {
        ConfigurationSection config = services.config().guiSection("dealer-gui");
        Gui gui = board(player, config, 5);
        gui.setItem(PREVIEW_SLOT, Item.builder().setItemProvider(icon(item, config, "items.offer.lore", OFFER_LORE,
                services.dealer().offers().getOrDefault(item, 0))).build());

        boolean[] acted = {false};
        long armAt = System.currentTimeMillis() + services.config().confirmArmMs();
        ItemStack confirm = MarketBrowseGUI.configItem(config, "items.confirm", player,
                Material.LIME_CONCRETE, "<green>ʙᴜʏ ꜰᴏʀ %price%", Map.of("%price%", price(item)));
        gui.setItem(CONFIRM_SLOT, Item.builder().setItemProvider(confirm)
                .addClickHandler(_ -> {
                    if (acted[0] || System.currentTimeMillis() < armAt) return;
                    acted[0] = true;
                    services.dealer().purchase(player, item.getId(), result -> afterPurchase(player, result));
                })
                .build());
        ItemStack cancel = MarketBrowseGUI.configItem(config, "items.cancel", player,
                Material.RED_CONCRETE, "<red>ᴄᴀɴᴄᴇʟ", Map.of());
        gui.setItem(CANCEL_SLOT, Item.builder().setItemProvider(cancel)
                .addClickHandler(_ -> {
                    if (acted[0]) return;
                    acted[0] = true;
                    open(player);
                })
                .build());

        show(player, gui, config != null ? config.getString("confirm-title", "Seal the deal?") : "Seal the deal?", () -> {
            if (!acted[0]) open(player);
            acted[0] = false;
        });
    }

    private void afterPurchase(Player player, DealResult result) {
        if (result.success()) {
            services.feedback().dealStruck(player);
        } else {
            services.feedback().refused(player);
        }
        tell(player, result);
        if (player.isOnline()) open(player);
    }

    private Gui board(Player player, @Nullable ConfigurationSection config, int rows) {
        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        String row = "# # # # # # # # #";
        return Gui.builder()
                .setStructure(Collections.nCopies(rows, row).toArray(String[]::new))
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();
    }

    private void show(Player player, Gui gui, String title, Runnable onClose) {
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(GuiUtil.title(player, title, Map.of(), gui.getHeight()))
                .addCloseHandler(_ -> onClose.run())
                .build()
                .open();
    }

    private ItemStack icon(DealerItem item, @Nullable ConfigurationSection config, String lorePath,
                           List<String> fallback, int stock) {
        List<String> template = config != null && config.isList(lorePath) ? config.getStringList(lorePath) : fallback;
        ItemStack icon = item.item();
        String price = price(item);
        icon.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);
            for (String line : template) {
                lore.add(MM.deserialize(line.replace("%price%", price).replace("%stock%", String.valueOf(stock)))
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        });
        return icon;
    }

    private static String price(DealerItem item) {
        return MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(item.getPrice())));
    }

    private static List<Integer> offerSlots(int count) {
        int step = count <= 5 ? 2 : 1;
        int start = 9 + (9 - (step * (count - 1) + 1)) / 2;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) slots.add(start + i * step);
        return slots;
    }

    private void tell(Player player, DealResult result) {
        player.sendMessage(MM.deserialize(services.config().message(result.getMessageKey(), result.getFallback())));
    }
}
