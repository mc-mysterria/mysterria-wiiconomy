package dev.ua.ikeepcalm.wiic.gui;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.services.PreferencesManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.Map;

/**
 * Confirmation dialog for coin deposit and withdrawal actions.
 *
 * <p>Configured via {@code action-gui} in {@code config.yml}:
 * <ul>
 *   <li>{@code title}        — MiniMessage title string</li>
 *   <li>{@code background}   — backdrop pane material</li>
 *   <li>{@code preview-slot} — {@code [x, y]} slot for the item being confirmed</li>
 *   <li>{@code items.confirm} — confirm button (standard item config)</li>
 *   <li>{@code items.cancel}  — cancel button (standard item config)</li>
 * </ul>
 *
 * <p>Default layout (3 rows, 9 wide):
 * <pre>
 *   Row 0:  # # # # # # # # #
 *   Row 1:  # # [confirm] # [preview] # [cancel] # #
 *   Row 2:  # # # # # # # # #
 * </pre>
 */
public class ActionGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Player player;
    private final ItemStack item;
    private final ConfirmationCallback callback;
    private final Runnable onClose;

    public ActionGUI(Player player, ItemStack item, ConfirmationCallback callback, Runnable onClose) {
        this.player = player;
        this.item   = item;
        this.callback = callback;
        this.onClose  = onClose;
    }

    public void open() {
        ConfigurationSection config = WIIC.INSTANCE.getConfig().getConfigurationSection("action-gui");

        Material bg = PreferencesManager.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        // Guard against processing a click while async work is already in flight
        boolean[] acted = {false};

        // Preview — show the actual item as-is (its own name/lore describe it)
        int previewSlot = config != null ? GuiUtil.slotIndex(config, "preview-slot") : 13;
        if (previewSlot < 0) previewSlot = 13;
        gui.setItem(previewSlot, Item.builder().setItemProvider(item.clone()).build());

        // Confirm button
        if (config != null) {
            ConfigurationSection confirmSection = config.getConfigurationSection("items.confirm");
            if (confirmSection != null) {
                int slot = GuiUtil.itemSlot(confirmSection);
                if (slot < 0) slot = 11;
                ItemStack btn = GuiUtil.createConfigItem(confirmSection, player);
                gui.setItem(slot, Item.builder()
                        .setItemProvider(btn)
                        .addClickHandler(_ -> {
                            if (acted[0]) return;
                            acted[0] = true;
                            callback.onConfirm(item);
                        })
                        .build());
            }

            // Cancel button
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
                    // Player pressed ESC without clicking confirm/cancel
                    if (!acted[0]) onClose.run();
                    acted[0] = false;
                })
                .build()
                .open();
    }

    public interface ConfirmationCallback {
        void onConfirm(ItemStack item);
        void onCancel();
    }
}
