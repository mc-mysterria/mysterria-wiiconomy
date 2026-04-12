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
 * Settings GUI — lets the player choose from the GUI theme variants
 * configured in {@code settings-gui.themes} in {@code config.yml}.
 *
 * <p>Each theme entry under {@code themes} is a standard item config section
 * (with {@code slot}, {@code material}, {@code name}, {@code lore}, etc.)
 * plus a {@code wallet-title} key: the texture-glyph string that will be used
 * as the WalletGUI window title when this theme is active.
 *
 * <p>An optional {@code items.back} section provides a "go back" button.
 * Pressing ESC also returns to the previous GUI.
 */
public class SettingsGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Player player;
    private final Runnable onBack;

    public SettingsGUI(Player player, Runnable onBack) {
        this.player = player;
        this.onBack = onBack;
    }

    public void open() {
        ConfigurationSection config = WIIC.INSTANCE.getConfig().getConfigurationSection("settings-gui");

        Material bg = GuiUtil.backgroundMaterial(config);
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        boolean[] acted = {false};

        if (config != null) {
            // Theme selection buttons
            ConfigurationSection themes = config.getConfigurationSection("themes");
            if (themes != null) {
                for (String themeId : themes.getKeys(false)) {
                    ConfigurationSection theme = themes.getConfigurationSection(themeId);
                    if (theme == null) continue;
                    int slot = GuiUtil.itemSlot(theme);
                    if (slot < 0 || slot >= 27) continue;

                    String currentTheme = PreferencesManager.getTheme(player.getUniqueId());
                    Map<String, String> extras = Map.of("%selected%", themeId.equals(currentTheme) ? "✔ " : "");
                    ItemStack btn = GuiUtil.createConfigItem(theme, player, extras);

                    final String capturedId = themeId;
                    gui.setItem(slot, Item.builder()
                            .setItemProvider(btn)
                            .addClickHandler(_ -> {
                                if (acted[0]) return;
                                acted[0] = true;
                                PreferencesManager.setTheme(player.getUniqueId(), capturedId);
                                onBack.run();
                            })
                            .build());
                }
            }

            // Back button (optional)
            ConfigurationSection backSection = config.getConfigurationSection("items.back");
            if (backSection != null) {
                int slot = GuiUtil.itemSlot(backSection);
                if (slot >= 0 && slot < 27) {
                    ItemStack btn = GuiUtil.createConfigItem(backSection, player);
                    gui.setItem(slot, Item.builder()
                            .setItemProvider(btn)
                            .addClickHandler(_ -> {
                                if (acted[0]) return;
                                acted[0] = true;
                                onBack.run();
                            })
                            .build());
                }
            }
        }

        String titleStr = config != null ? config.getString("title", "") : "";
        var title = MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of()));

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
}
