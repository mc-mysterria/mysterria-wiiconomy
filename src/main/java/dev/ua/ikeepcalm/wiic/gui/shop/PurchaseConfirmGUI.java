package dev.ua.ikeepcalm.wiic.gui.shop;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import dev.ua.ikeepcalm.wiic.domain.shop.service.PurchaseService;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopEntry;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.Map;

/**
 * Final {@code /shop} confirmation — the second of the two required confirmations
 * (the first being the "continue" button on {@link QuantityGUI}). This is the only
 * screen that actually calls {@link PurchaseService#purchase}.
 *
 * <p>The confirm button additionally ignores clicks until {@code limits.confirm-arm-ms}
 * has elapsed since the screen opened, so a double-click carried over from the
 * previous screen (or a delayed/replayed packet) can't land on it. A re-entrancy
 * guard (the same {@code boolean[] acted} idiom used by {@code ActionGUI}/{@code
 * SellingGUI}) then ensures only the first accepted click is ever acted on.
 *
 * <p>Configured via {@code confirm-gui} in {@code shop.yml}: {@code title},
 * {@code background}, {@code items.preview} (slot only), {@code items.confirm}
 * (supports {@code %amount%}/{@code %item%}/{@code %total%}), {@code items.cancel}.
 */
public class PurchaseConfirmGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int DEFAULT_PREVIEW_SLOT = 13;

    private final ShopServices services;
    private final Runnable onBack;

    public PurchaseConfirmGUI(ShopServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player, ShopEntry entry, int amount) {
        long unitPrice = services.pricing().unitPrice(entry.material());
        if (unitPrice < 0) {
            player.sendMessage(MM.deserialize(services.config().message("purchase-failed", "<red>Purchase failed — please contact an administrator.")));
            onBack.run();
            return;
        }
        long total = unitPrice * amount;

        ConfigurationSection config = services.config().raw().getConfigurationSection("confirm-gui");

        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        int previewSlot = config != null ? GuiUtil.itemSlot(config.getConfigurationSection("items.preview")) : DEFAULT_PREVIEW_SLOT;
        if (previewSlot < 0) previewSlot = DEFAULT_PREVIEW_SLOT;
        gui.setItem(previewSlot, Item.builder().setItemProvider(new ItemStack(entry.material(), Math.min(amount, entry.material().getMaxStackSize()))).build());

        boolean[] acted = {false};
        long armAt = System.currentTimeMillis() + services.config().confirmArmMs();

        if (config != null) {
            Map<String, String> extras = Map.of(
                    "%amount%", String.valueOf(amount),
                    "%item%", entry.displayName(),
                    "%total%", plain(CoinUtil.getFormattedPrice(clampToInt(total)))
            );

            ConfigurationSection confirmSection = config.getConfigurationSection("items.confirm");
            if (confirmSection != null) {
                int slot = GuiUtil.itemSlot(confirmSection);
                if (slot < 0) slot = 20;
                ItemStack btn = GuiUtil.createConfigItem(confirmSection, player, extras);
                gui.setItem(slot, Item.builder().setItemProvider(btn)
                        .addClickHandler(_ -> {
                            if (acted[0]) return;
                            if (System.currentTimeMillis() < armAt) return; // ignore clicks carried over from the previous screen
                            acted[0] = true;
                            services.purchaseService().purchase(player, entry.material(), amount, unitPrice,
                                    outcome -> handleOutcome(player, entry, amount, outcome));
                        })
                        .build());
            }

            ConfigurationSection cancelSection = config.getConfigurationSection("items.cancel");
            if (cancelSection != null) {
                int slot = GuiUtil.itemSlot(cancelSection);
                if (slot < 0) slot = 24;
                ItemStack btn = GuiUtil.createConfigItem(cancelSection, player);
                gui.setItem(slot, Item.builder().setItemProvider(btn)
                        .addClickHandler(_ -> {
                            if (acted[0]) return;
                            acted[0] = true;
                            onBack.run();
                        })
                        .build());
            }
        }

        String titleStr = config != null ? config.getString("title", "Confirm purchase") : "Confirm purchase";
        Component title = MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of()));

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(title)
                .addCloseHandler(_ -> {
                    if (!acted[0]) onBack.run();
                    acted[0] = false;
                })
                .build()
                .open();
    }

    private void handleOutcome(Player player, ShopEntry entry, int amount, PurchaseService.PurchaseOutcome outcome) {
        switch (outcome.result()) {
            case SUCCESS -> {
                String msg = services.config().message("purchase-success", "<green>Bought %amount%x %item% for %total%.")
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%item%", entry.displayName())
                        .replace("%total%", plain(CoinUtil.getFormattedPrice(clampToInt(outcome.chargedTotal()))));
                player.sendMessage(MM.deserialize(msg));
                if (outcome.droppedStacks() > 0) {
                    player.sendMessage(MM.deserialize(services.config().message("inventory-full-drop",
                            "<yellow>Your inventory couldn't hold everything — the rest was dropped at your feet.")));
                }
            }
            case ALREADY_IN_PROGRESS -> player.sendMessage(MM.deserialize(services.config().message("in-progress", "<red>Please finish or cancel your current purchase first.")));
            case COOLDOWN -> player.sendMessage(MM.deserialize(services.config().message("cooldown", "<red>Please wait a moment before purchasing again.")));
            case PRICE_CHANGED -> player.sendMessage(MM.deserialize(services.config().message("price-changed", "<red>The price changed while you were confirming — please try again.")));
            case INSUFFICIENT_FUNDS -> player.sendMessage(MM.deserialize(services.config().message("insufficient-funds", "<red>You don't have enough money for this purchase.")));
            case PLAYER_OFFLINE -> { /* player already left — refund is logged, nothing to show them */ }
            case NOT_PURCHASABLE, INVALID_AMOUNT, WITHDRAW_FAILED ->
                    player.sendMessage(MM.deserialize(services.config().message("purchase-failed", "<red>Purchase failed — please contact an administrator.")));
        }
        onBack.run();
    }

    private static int clampToInt(long value) {
        return Math.clamp(value, 0, Integer.MAX_VALUE);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
