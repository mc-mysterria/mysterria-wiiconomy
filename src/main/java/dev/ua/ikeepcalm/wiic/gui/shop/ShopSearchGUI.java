package dev.ua.ikeepcalm.wiic.gui.shop;

import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopEntry;
import dev.ua.ikeepcalm.wiic.domain.shop.service.ShopServices;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.AnvilWindow;

import java.util.List;

/**
 * Name-search prompt for {@code /shop}, backed by a vanilla anvil text field
 * (matches the "type amount" input on {@link QuantityGUI}). Typing a substring
 * and clicking the result slot opens {@link ItemsGUI} with the matches.
 *
 * <p>Configured via {@code search-gui} in {@code shop.yml}: {@code title} only.
 */
public class ShopSearchGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ShopServices services;
    private final Runnable onBack;

    public ShopSearchGUI(ShopServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player) {
        String[] latest = {""};
        boolean[] navigated = {false};

        Gui upperGui = Gui.builder()
                .setStructure("i # r")
                .addIngredient('i', Item.builder().setItemProvider(new ItemStack(Material.PAPER)).build())
                .addIngredient('#', GuiUtil.emptyPane(Material.GRAY_STAINED_GLASS_PANE))
                .addIngredient('r', Item.builder().setItemProvider(new ItemStack(Material.COMPASS))
                        .addClickHandler(_ -> {
                            String query = latest[0];
                            List<ShopEntry> results = services.catalog().search(query);
                            if (results.isEmpty()) {
                                player.sendMessage(MM.deserialize(services.config().message("no-results", "<red>No matching blocks found.")));
                                return;
                            }
                            navigated[0] = true;
                            new ItemsGUI(services, () -> open(player)).openResults(player, results, query);
                        })
                        .build())
                .build();

        ConfigurationSection config = services.config().raw().getConfigurationSection("search-gui");
        String titleStr = config != null ? config.getString("title", "Search market") : "Search market";

        AnvilWindow.builder()
                .setViewer(player)
                .setUpperGui(upperGui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, java.util.Map.of())))
                .setTextFieldAlwaysEnabled(true)
                .setResultAlwaysValid(true)
                .addRenameHandler(text -> latest[0] = text)
                .addCloseHandler(_ -> {
                    if (!navigated[0]) onBack.run();
                })
                .build()
                .open();
    }
}
