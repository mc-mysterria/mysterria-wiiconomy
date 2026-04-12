package dev.ua.ikeepcalm.wiic.gui;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import dev.ua.ikeepcalm.wiic.currency.services.PreferencesManager;
import dev.ua.ikeepcalm.wiic.currency.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.currency.services.SoldItemsManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Main Wallet GUI — the player's hub for balance, stats, and navigation.
 *
 * <p>Fully config-driven via {@code wallet-gui} in {@code config.yml}:
 * <ul>
 *   <li>{@code title}      — MiniMessage string used as the default wallet title
 *       (overridden by the player's chosen theme from {@code settings-gui.themes})</li>
 *   <li>{@code background} — Material name for the glass-pane backdrop</li>
 *   <li>{@code items.*}    — Each item: {@code slot [x,y]}, {@code material},
 *       {@code name}, {@code lore}, {@code item-model}, {@code custom-model-data}</li>
 * </ul>
 *
 * <p>Special item keys:
 * <ul>
 *   <li>{@code balance}  — opens {@link VaultGUI} on click</li>
 *   <li>{@code settings} — opens {@link SettingsGUI} on click</li>
 * </ul>
 */
public class WalletGUI {

    public static final Set<Player> playersWithOpenWallets = new HashSet<>();

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;

    public WalletGUI(PriceAppraiser priceAppraiser, SoldItemsManager soldItemsManager) {
        this.priceAppraiser = priceAppraiser;
        this.soldItemsManager = soldItemsManager;
    }

    public void open(Player player, WalletData data, Runnable onClose) {
        ConfigurationSection config = WIIC.INSTANCE.getConfig().getConfigurationSection("wallet-gui");
        if (config == null) {
            player.sendMessage(Component.text("wallet-gui section missing in config.yml")
                    .color(NamedTextColor.RED));
            return;
        }

        // Navigating to VaultGUI / SettingsGUI must not fire onClose
        boolean[] callOnClose = {true};

        Material bg = GuiUtil.backgroundMaterial(config);
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        ConfigurationSection items = config.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(key);
                if (section == null) continue;

                int slot = GuiUtil.itemSlot(section);
                if (slot < 0) continue;

                ItemStack item = GuiUtil.createConfigItem(section, player);

                switch (key) {
                    case "balance" ->
                        gui.setItem(slot, Item.builder()
                                .setItemProvider(item)
                                .addClickHandler(_ -> {
                                    callOnClose[0] = false;
                                    new VaultGUI(priceAppraiser, soldItemsManager).openVault(player, onClose);
                                })
                                .build());

                    case "settings" ->
                        gui.setItem(slot, Item.builder()
                                .setItemProvider(item)
                                .addClickHandler(_ -> {
                                    callOnClose[0] = false;
                                    new SettingsGUI(player, () -> reopen(player, onClose)).open();
                                })
                                .build());

                    default ->
                        gui.setItem(slot, Item.builder().setItemProvider(item).build());
                }
            }
        }

        // Resolve title: prefer the player's saved theme; fall back to wallet-gui.title
        String titleStr = resolveTitle(player, config);
        Component title = MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of()));

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(title)
                .addCloseHandler(_ -> {
                    if (callOnClose[0]) onClose.run();
                })
                .build()
                .open();
    }

    /**
     * Re-fetches the player's balance asynchronously and reopens the wallet GUI.
     * Used by SettingsGUI to navigate back after a theme change.
     */
    public void reopen(Player player, Runnable onClose) {
        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
            if (WIIC.getEcon() == null) return;
            BigDecimal balance = WIIC.getEcon().balance("iConomyUnlocked", player.getUniqueId());
            WalletData data = new WalletData(balance.intValue());
            Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> open(player, data, onClose));
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the MiniMessage title string for the given player.
     * Looks up the player's saved theme in {@code settings-gui.themes.<themeId>.wallet-title};
     * falls back to {@code wallet-gui.title} if no theme is saved or the theme entry is missing.
     */
    private static String resolveTitle(Player player, ConfigurationSection walletConfig) {
        String themeId = PreferencesManager.getTheme(player.getUniqueId());
        if (!themeId.isEmpty()) {
            ConfigurationSection themeSection = WIIC.INSTANCE.getConfig()
                    .getConfigurationSection("settings-gui.themes." + themeId);
            if (themeSection != null) {
                String themed = themeSection.getString("wallet-title");
                if (themed != null && !themed.isEmpty()) return themed;
            }
        }
        return walletConfig.getString("title", "Wallet");
    }
}
