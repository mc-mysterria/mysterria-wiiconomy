package dev.ua.ikeepcalm.wiic.gui.shop;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import dev.ua.ikeepcalm.wiic.domain.shop.model.source.ShopCategory;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopEntry;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
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
 * Third-level {@code /shop} screen — a manually paginated grid (36 items per page,
 * rows 1-4) of catalogue entries, either a family's full item list or a search
 * result set. Each item shows its live unit price and opens {@link QuantityGUI}.
 *
 * <p>Configured via {@code items-gui} in {@code shop.yml}: {@code title} (supports
 * {@code %family%}), {@code background}, and {@code items.back} / {@code
 * previous-page} / {@code next-page} / {@code page-indicator} (material/name/lore
 * only — these five are always placed at fixed slots, not config-positioned) /
 * {@code item-price} ({@code lore} appended to every catalogue item, supports
 * {@code %unit-price%}).
 */
public final class ItemsGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 36;
    private static final int CONTENT_START = 9;
    private static final int BACK_SLOT = 0;
    private static final int PREVIOUS_SLOT = 47;
    private static final int PAGE_INDICATOR_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final ShopServices services;
    private final Runnable onBack;

    public ItemsGUI(ShopServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player, ShopCategory category, String family) {
        List<ShopEntry> entries = services.catalog().entries(category, family);
        Map<String, String> extras = Map.of("%family%", GuiUtil.prettify(family));
        render(player, entries, extras, 0);
    }

    public void openResults(Player player, List<ShopEntry> results, String query) {
        Map<String, String> extras = Map.of("%family%", "\"" + query + "\"");
        render(player, results, extras, 0);
    }

    private void render(Player player, List<ShopEntry> entries, Map<String, String> titleExtras, int page) {
        ConfigurationSection config = services.config().raw().getConfigurationSection("items-gui");

        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        int clampedPage = Math.max(0, Math.min(page, totalPages - 1));

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

        if (config != null) {
            ConfigurationSection backSection = config.getConfigurationSection("items.back");
            if (backSection != null) {
                ItemStack btn = GuiUtil.createConfigItem(backSection, player);
                gui.setItem(BACK_SLOT, Item.builder().setItemProvider(btn)
                        .addClickHandler(_ -> onBack.run())
                        .build());
            }

            if (clampedPage > 0) {
                ConfigurationSection prevSection = config.getConfigurationSection("items.previous-page");
                if (prevSection != null) {
                    ItemStack btn = GuiUtil.createConfigItem(prevSection, player);
                    gui.setItem(PREVIOUS_SLOT, Item.builder().setItemProvider(btn)
                            .addClickHandler(_ -> render(player, entries, titleExtras, clampedPage - 1))
                            .build());
                }
            }

            if (clampedPage < totalPages - 1) {
                ConfigurationSection nextSection = config.getConfigurationSection("items.next-page");
                if (nextSection != null) {
                    ItemStack btn = GuiUtil.createConfigItem(nextSection, player);
                    gui.setItem(NEXT_SLOT, Item.builder().setItemProvider(btn)
                            .addClickHandler(_ -> render(player, entries, titleExtras, clampedPage + 1))
                            .build());
                }
            }

            ConfigurationSection indicatorSection = config.getConfigurationSection("items.page-indicator");
            if (indicatorSection != null) {
                Map<String, String> pageExtras = Map.of(
                        "%page%", String.valueOf(clampedPage + 1),
                        "%pages%", String.valueOf(totalPages));
                ItemStack indicator = GuiUtil.createConfigItem(indicatorSection, player, pageExtras);
                gui.setItem(PAGE_INDICATOR_SLOT, Item.builder().setItemProvider(indicator).build());
            }
        }

        List<String> priceLoreTemplate = config != null
                ? config.getStringList("items.item-price.lore")
                : List.of();

        int from = clampedPage * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);
        int slot = CONTENT_START;
        for (int i = from; i < to; i++) {
            ShopEntry entry = entries.get(i);
            long unitPrice = services.pricing().unitPrice(entry.material());
            ItemStack display = buildEntryDisplay(entry, unitPrice, priceLoreTemplate);

            gui.setItem(slot, Item.builder().setItemProvider(display)
                    .addClickHandler(_ -> new QuantityGUI(services, () -> render(player, entries, titleExtras, clampedPage))
                            .open(player, entry))
                    .build());
            slot++;
        }

        String titleStr = config != null ? config.getString("title", "Shop") : "Shop";
        Component title = MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, titleExtras));

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(title)
                .build()
                .open();
    }

    private static ItemStack buildEntryDisplay(ShopEntry entry, long unitPrice, List<String> loreTemplate) {
        ItemStack display = new ItemStack(entry.material(), 1);
        if (loreTemplate.isEmpty()) return display;

        String priceFormatted = PlainTextComponentSerializer.plainText()
                .serialize(CoinUtil.getFormattedPrice((int) Math.min(Integer.MAX_VALUE, unitPrice)));

        display.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);
            for (String line : loreTemplate) {
                String resolved = line.replace("%unit-price%", priceFormatted);
                lore.add(resolved.isEmpty()
                        ? Component.empty()
                        : MM.deserialize(resolved).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        });
        return display;
    }
}
