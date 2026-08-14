package dev.ua.ikeepcalm.wiic.gui.shop;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import dev.ua.ikeepcalm.wiic.domain.shop.model.source.ShopCategory;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.Locale;
import java.util.Map;

/**
 * Root {@code /shop} screen — category icons plus balance/market-index/search shortcuts.
 *
 * <p>Configured via {@code shop-gui} in {@code shop.yml}: {@code title}, {@code background},
 * {@code items.*} (standard item keys; {@code balance} supports {@code %balance%},
 * {@code market-index} supports {@code %market-index%}, {@code search} opens
 * {@link ShopSearchGUI}), and {@code categories.<key>} (one per {@link ShopCategory},
 * opens {@link FamilyGUI} for that category).
 */
public class ShopGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ShopServices services;

    public ShopGUI(ShopServices services) {
        this.services = services;
    }

    public void open(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
            double balance = VaultUtil.getBalance(player.getUniqueId()).join();
            Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> openSync(player, balance));
        });
    }

    private void openSync(Player player, double balance) {
        ConfigurationSection config = services.config().raw().getConfigurationSection("shop-gui");
        if (config == null) {
            player.sendMessage(MM.deserialize("<red>shop-gui section missing in shop.yml!"));
            return;
        }

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

        Map<String, String> extras = Map.of(
                "%balance%", String.format(Locale.ROOT, "%,.2f", balance),
                "%market-index%", String.format(Locale.ROOT, "%.2fx", services.marketIndex().currentIndex())
        );

        ConfigurationSection items = config.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(key);
                if (section == null) continue;
                int slot = GuiUtil.itemSlot(section);
                if (slot < 0 || slot >= 54) continue;
                ItemStack item = GuiUtil.createConfigItem(section, player, extras);

                if ("search".equals(key)) {
                    gui.setItem(slot, Item.builder().setItemProvider(item)
                            .addClickHandler(_ -> new ShopSearchGUI(services, () -> open(player)).open(player))
                            .build());
                } else {
                    gui.setItem(slot, Item.builder().setItemProvider(item).build());
                }
            }
        }

        ConfigurationSection categories = config.getConfigurationSection("categories");
        if (categories != null) {
            for (ShopCategory category : services.catalog().categories()) {
                ConfigurationSection section = categories.getConfigurationSection(category.configKey());
                if (section == null) continue;
                int slot = GuiUtil.itemSlot(section);
                if (slot < 0 || slot >= 54) continue;
                ItemStack item = GuiUtil.createConfigItem(section, player);
                gui.setItem(slot, Item.builder().setItemProvider(item)
                        .addClickHandler(_ -> new FamilyGUI(services, () -> open(player)).open(player, category))
                        .build());
            }
        }

        Component title = MM.deserialize(GuiUtil.replacePlaceholders(player, config.getString("title", "Shop"), Map.of()));

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(title)
                .build()
                .open();
    }
}
