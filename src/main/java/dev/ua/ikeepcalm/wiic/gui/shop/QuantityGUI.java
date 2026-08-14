package dev.ua.ikeepcalm.wiic.gui.shop;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
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
import xyz.xenondevs.invui.window.AnvilWindow;
import xyz.xenondevs.invui.window.Window;

import java.util.Map;

/**
 * Fourth-level {@code /shop} screen — choose how many of {@link ShopEntry} to buy.
 * Preset step buttons (±1/±8/±64, quick presets 1/16/64/320/640) plus a "type
 * amount" button that opens a vanilla anvil text field for an exact number.
 * "Continue" hands off to {@link PurchaseConfirmGUI} — the first of the two
 * required confirmations before any money moves.
 *
 * <p>Configured via {@code quantity-gui} in {@code shop.yml}: {@code title}
 * (supports {@code %item%}), {@code background}, {@code items.preview} (slot
 * only — always shows the real item stack), the {@code decrease-*}/{@code
 * increase-*}/{@code preset-*}/{@code type-amount} buttons, and {@code
 * items.confirm} (supports {@code %amount%}/{@code %total%}) / {@code items.cancel}.
 */
public class QuantityGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int DEFAULT_PREVIEW_SLOT = 13;

    private final ShopServices services;
    private final Runnable onBack;

    public QuantityGUI(ShopServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player, ShopEntry entry) {
        open(player, entry, 1);
    }

    private void open(Player player, ShopEntry entry, int amount) {
        int max = services.config().maxPerPurchase();
        int clamped = Math.max(1, Math.min(amount, max));

        ConfigurationSection config = services.config().raw().getConfigurationSection("quantity-gui");

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
        gui.setItem(previewSlot, Item.builder().setItemProvider(new ItemStack(entry.material(), clamped)).build());

        if (config != null) {
            step(gui, player, config, "decrease-1", entry, clamped, -1);
            step(gui, player, config, "decrease-8", entry, clamped, -8);
            step(gui, player, config, "decrease-64", entry, clamped, -64);
            step(gui, player, config, "increase-1", entry, clamped, 1);
            step(gui, player, config, "increase-8", entry, clamped, 8);
            step(gui, player, config, "increase-64", entry, clamped, 64);

            preset(gui, player, config, "preset-1", entry, 1);
            preset(gui, player, config, "preset-16", entry, 16);
            preset(gui, player, config, "preset-64", entry, 64);
            preset(gui, player, config, "preset-320", entry, 320);
            preset(gui, player, config, "preset-640", entry, 640);

            ConfigurationSection typeSection = config.getConfigurationSection("items.type-amount");
            if (typeSection != null) {
                int slot = GuiUtil.itemSlot(typeSection);
                if (slot >= 0 && slot < 54) {
                    ItemStack btn = GuiUtil.createConfigItem(typeSection, player);
                    gui.setItem(slot, Item.builder().setItemProvider(btn)
                            .addClickHandler(_ -> openTypeAmount(player, entry, clamped))
                            .build());
                }
            }

            long unitPrice = services.pricing().unitPrice(entry.material());
            long total = unitPrice * clamped;
            Map<String, String> extras = Map.of(
                    "%amount%", String.valueOf(clamped),
                    "%total%", plain(CoinUtil.getFormattedPrice(clampToInt(total)))
            );

            ConfigurationSection confirmSection = config.getConfigurationSection("items.confirm");
            if (confirmSection != null) {
                int slot = GuiUtil.itemSlot(confirmSection);
                if (slot >= 0 && slot < 54) {
                    ItemStack btn = GuiUtil.createConfigItem(confirmSection, player, extras);
                    final int confirmedAmount = clamped;
                    gui.setItem(slot, Item.builder().setItemProvider(btn)
                            .addClickHandler(_ -> new PurchaseConfirmGUI(services, () -> open(player, entry, confirmedAmount))
                                    .open(player, entry, confirmedAmount))
                            .build());
                }
            }

            ConfigurationSection cancelSection = config.getConfigurationSection("items.cancel");
            if (cancelSection != null) {
                int slot = GuiUtil.itemSlot(cancelSection);
                if (slot >= 0 && slot < 54) {
                    ItemStack btn = GuiUtil.createConfigItem(cancelSection, player);
                    gui.setItem(slot, Item.builder().setItemProvider(btn)
                            .addClickHandler(_ -> onBack.run())
                            .build());
                }
            }
        }

        String titleStr = config != null ? config.getString("title", "Quantity") : "Quantity";
        Map<String, String> titleExtras = Map.of("%item%", entry.displayName());
        Component title = MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, titleExtras));

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(title)
                .build()
                .open();
    }

    private void step(Gui gui, Player player, ConfigurationSection config, String key, ShopEntry entry, int currentAmount, int delta) {
        ConfigurationSection section = config.getConfigurationSection("items." + key);
        if (section == null) return;
        int slot = GuiUtil.itemSlot(section);
        if (slot < 0 || slot >= 54) return;
        ItemStack btn = GuiUtil.createConfigItem(section, player);
        gui.setItem(slot, Item.builder().setItemProvider(btn)
                .addClickHandler(_ -> open(player, entry, currentAmount + delta))
                .build());
    }

    private void preset(Gui gui, Player player, ConfigurationSection config, String key, ShopEntry entry, int amount) {
        ConfigurationSection section = config.getConfigurationSection("items." + key);
        if (section == null) return;
        int slot = GuiUtil.itemSlot(section);
        if (slot < 0 || slot >= 54) return;
        ItemStack btn = GuiUtil.createConfigItem(section, player);
        gui.setItem(slot, Item.builder().setItemProvider(btn)
                .addClickHandler(_ -> open(player, entry, amount))
                .build());
    }

    /** Opens a vanilla anvil text field for typing an exact amount. */
    private void openTypeAmount(Player player, ShopEntry entry, int currentAmount) {
        String[] latest = {String.valueOf(currentAmount)};

        Gui upperGui = Gui.builder()
                .setStructure("i # r")
                .addIngredient('i', Item.builder().setItemProvider(new ItemStack(Material.PAPER)).build())
                .addIngredient('#', GuiUtil.emptyPane(Material.GRAY_STAINED_GLASS_PANE))
                .addIngredient('r', Item.builder().setItemProvider(new ItemStack(Material.LIME_STAINED_GLASS_PANE))
                        .addClickHandler(_ -> {
                            int parsed = parseAmount(latest[0]);
                            if (parsed < 1) {
                                int max = services.config().maxPerPurchase();
                                String msg = services.config().message("invalid-amount", "<red>Enter a whole number between 1 and %max%.")
                                        .replace("%max%", String.valueOf(max));
                                player.sendMessage(MM.deserialize(msg));
                                return;
                            }
                            open(player, entry, parsed);
                        })
                        .build())
                .build();

        AnvilWindow.builder()
                .setViewer(player)
                .setUpperGui(upperGui)
                .setTitle(MM.deserialize("<white>Type an amount"))
                .setTextFieldAlwaysEnabled(true)
                .setResultAlwaysValid(true)
                .addRenameHandler(text -> latest[0] = text)
                .build()
                .open();
    }

    private static int parseAmount(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int clampToInt(long value) {
        return Math.clamp(value, 0, Integer.MAX_VALUE);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
