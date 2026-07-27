package dev.ua.ikeepcalm.wiic.gui.shop;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import dev.ua.ikeepcalm.wiic.domain.shop.model.source.ShopCategory;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopEntry;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.List;
import java.util.Map;

/**
 * Second-level {@code /shop} screen — lists the families (variant groups: wood
 * species, dye colors, stone types, …) within one {@link ShopCategory}, so a player
 * can jump straight to "the 16 wool colors" instead of scrolling every block.
 *
 * <p>Configured via {@code family-gui} in {@code shop.yml}: {@code title}
 * (supports {@code %category%}), {@code background}, {@code items.back}.
 * Family tiles themselves are generated from the live catalogue, not configured
 * per-family — there can be a different family set per category.
 */
public final class FamilyGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 45; // exclusive — leaves the last row for navigation

    private final ShopServices services;
    private final Runnable onBack;

    public FamilyGUI(ShopServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player, ShopCategory category) {
        ConfigurationSection config = services.config().raw().getConfigurationSection("family-gui");

        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(),
                GuiUtil.backgroundMaterial(config));
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

        if (config != null) {
            ConfigurationSection backSection = config.getConfigurationSection("items.back");
            if (backSection != null) {
                int slot = GuiUtil.itemSlot(backSection);
                if (slot >= 0 && slot < 54) {
                    ItemStack btn = GuiUtil.createConfigItem(backSection, player);
                    gui.setItem(slot, Item.builder().setItemProvider(btn)
                            .addClickHandler(_ -> onBack.run())
                            .build());
                }
            }
        }

        int slot = CONTENT_START;
        for (String family : services.catalog().families(category)) {
            if (slot >= CONTENT_END) break; // more families than fit — shouldn't happen for our category set
            List<ShopEntry> entries = services.catalog().entries(category, family);
            if (entries.isEmpty()) continue;

            ShopEntry icon = pickIcon(category, entries);
            ItemStack display = new ItemStack(icon.material());
            display.editMeta(meta -> {
                meta.displayName(MM.deserialize("<white>" + GuiUtil.prettify(family))
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        MM.deserialize("<gray>" + entries.size() + " items")
                                .decoration(TextDecoration.ITALIC, false),
                        MM.deserialize("<dark_gray>ᴄʟɪᴄᴋ ᴛᴏ ʙʀᴏᴡsᴇ")
                                .decoration(TextDecoration.ITALIC, false)
                ));
            });

            final String capturedFamily = family;
            gui.setItem(slot, Item.builder().setItemProvider(display)
                    .addClickHandler(_ -> new ItemsGUI(services, () -> open(player, category)).open(player, category, capturedFamily))
                    .build());
            slot++;
        }

        String titleStr = config != null ? config.getString("title", "Shop") : "Shop";
        Map<String, String> extras = Map.of("%category%", GuiUtil.prettify(category.configKey()));
        Component title = MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, extras));

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(title)
                .build()
                .open();
    }

    /**
     * Picks the family tile's icon. The alphabetically-first entry is often an
     * accessory (a button, a banner) rather than the block a player thinks of as
     * "the wood" or "the wool" — prefer the representative block for categories
     * where that mismatch is obvious.
     */
    private static ShopEntry pickIcon(ShopCategory category, List<ShopEntry> entries) {
        String preferredSuffix = switch (category) {
            case WOOD -> "_PLANKS";
            case COLOR -> "_WOOL";
            default -> null;
        };
        if (preferredSuffix != null) {
            for (ShopEntry entry : entries) {
                if (entry.material().name().endsWith(preferredSuffix)) return entry;
            }
        }
        return entries.get(0);
    }
}
