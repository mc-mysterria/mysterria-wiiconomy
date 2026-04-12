package dev.ua.ikeepcalm.wiic.gui;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.services.PreferencesManager;
import dev.ua.ikeepcalm.wiic.currency.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.currency.services.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Selling confirmation dialog shown when a player clicks a sellable item in VaultGUI.
 *
 * <p>Configured via {@code selling-gui} in {@code config.yml}:
 * <ul>
 *   <li>{@code title}                 — MiniMessage title string</li>
 *   <li>{@code background}            — backdrop pane material</li>
 *   <li>{@code preview-slot}          — {@code [x, y]} slot for the item preview</li>
 *   <li>{@code items.confirm-available}   — sell button when under daily limit;
 *       supports {@code %price%} placeholder in name/lore</li>
 *   <li>{@code items.confirm-unavailable} — sell button when limit is exceeded / unsellable</li>
 *   <li>{@code items.cancel}              — cancel / go-back button</li>
 * </ul>
 *
 * <p>The preview item has the detailed appraisal appended to its lore so players
 * can see the price breakdown without leaving the item's natural appearance.
 *
 * <p>Default layout (3 rows, 9 wide):
 * <pre>
 *   Row 0:  # # # # # # # # #
 *   Row 1:  # # [confirm] # [preview+price] # [cancel] # #
 *   Row 2:  # # # # # # # # #
 * </pre>
 */
public class SellingGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Player player;
    private final ItemStack item;
    private final ConfirmationCallback callback;
    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;
    private final Runnable onClose;

    public SellingGUI(Player player, ItemStack item, ConfirmationCallback callback,
                      PriceAppraiser priceAppraiser, SoldItemsManager soldItemsManager,
                      Runnable onClose) {
        this.player = player;
        this.item = item;
        this.callback = callback;
        this.priceAppraiser = priceAppraiser;
        this.soldItemsManager = soldItemsManager;
        this.onClose = onClose;
    }

    public void open() {
        ConfigurationSection config = WIIC.INSTANCE.getConfig().getConfigurationSection("selling-gui");

        int appraisal = priceAppraiser.appraise(item);
        int available = soldItemsManager.getAvailableSellingAmount(player);
        List<Component> appraisalDetails = priceAppraiser.getDetailedAppraisal(item, available);
        boolean canSell = appraisal > 0 && appraisal <= available;

        Material bg = PreferencesManager.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        boolean[] acted = {false};

        // Lore strings — configurable with sensible defaults
        String separator = canSell
                ? (config != null ? config.getString("lore.separator-available", "<dark_green>──────────────") : "<dark_green>──────────────")
                : (config != null ? config.getString("lore.separator-unavailable", "<dark_red>──────────────") : "<dark_red>──────────────");
        String unavailLabel = appraisal <= 0
                ? (config != null ? config.getString("lore.not-sellable", "<red>ɴᴏᴛ sᴇʟʟᴀʙʟᴇ") : "<red>ɴᴏᴛ sᴇʟʟᴀʙʟᴇ")
                : (config != null ? config.getString("lore.daily-limit", "<gold>ᴅᴀɪʟʏ ʟɪᴍɪᴛ ʀᴇᴀᴄʜᴇᴅ") : "<gold>ᴅᴀɪʟʏ ʟɪᴍɪᴛ ʀᴇᴀᴄʜᴇᴅ");

        // Preview — clone the item and append appraisal details to its lore
        int previewSlot = config != null ? GuiUtil.slotIndex(config, "preview-slot") : 13;
        if (previewSlot < 0) previewSlot = 13;

        ItemStack preview = buildPreview(appraisalDetails, canSell, appraisal, separator, unavailLabel);
        gui.setItem(previewSlot, Item.builder().setItemProvider(preview).build());

        if (config != null) {
            String priceFormatted = PlainTextComponentSerializer.plainText()
                    .serialize(CoinUtil.getFormattedPrice(appraisal));
            Map<String, String> extras = Map.of("%price%", priceFormatted);

            // Confirm — switches between available / unavailable variant
            String confirmKey = canSell ? "items.confirm-available" : "items.confirm-unavailable";
            ConfigurationSection confirmSection = config.getConfigurationSection(confirmKey);
            if (confirmSection != null) {
                int slot = GuiUtil.itemSlot(confirmSection);
                if (slot < 0) slot = 11;
                ItemStack btn = GuiUtil.createConfigItem(confirmSection, player, extras);
                gui.setItem(slot, Item.builder()
                        .setItemProvider(btn)
                        .addClickHandler(_ -> {
                            if (acted[0] || !canSell) return;
                            acted[0] = true;
                            callback.onConfirm(item);
                        })
                        .build());
            }

            // Cancel
            ConfigurationSection cancelSection = config.getConfigurationSection("items.cancel");
            if (cancelSection != null) {
                int slot = GuiUtil.itemSlot(cancelSection);
                if (slot < 0) slot = 15;
                ItemStack btn = GuiUtil.createConfigItem(cancelSection, player);
                gui.setItem(slot, Item.builder()
                        .setItemProvider(btn)
                        .addClickHandler(_ -> {
                            if (acted[0]) return;
                            acted[0] = true;
                            callback.onCancel();
                        })
                        .build());
            }
        }

        String titleStr = config != null ? config.getString("title", "") : "";
        var title = MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of()));

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(title)
                .addCloseHandler(_ -> {
                    if (!acted[0]) onClose.run();
                    acted[0] = false;
                })
                .build()
                .open();
    }

    /**
     * Clones the item and appends a separator + detailed appraisal lines to its lore,
     * so the player can see the price breakdown on the preview item itself.
     *
     * @param separator    MiniMessage string for the divider line (green or red depending on canSell)
     * @param unavailLabel MiniMessage string shown below the divider when the item cannot be sold
     */
    private ItemStack buildPreview(List<Component> appraisalDetails, boolean canSell, int appraisal,
                                   String separator, String unavailLabel) {
        ItemStack preview = item.clone();
        preview.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            // Preserve any existing lore
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);

            lore.add(Component.empty());
            lore.add(MM.deserialize(separator).decoration(TextDecoration.ITALIC, false));
            lore.addAll(appraisalDetails);
            if (!canSell) {
                lore.add(Component.empty());
                lore.add(MM.deserialize(unavailLabel).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        });
        return preview;
    }

    public interface ConfirmationCallback {
        void onConfirm(ItemStack item);
        void onCancel();
    }
}
