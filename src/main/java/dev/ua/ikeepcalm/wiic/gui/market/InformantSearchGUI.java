package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.List;
import java.util.Map;

/**
 * The Informant: lists every pathway that currently has beyonder goods on offer
 * (from the CoI PDC snapshot columns), and opens the browse screen filtered to
 * the chosen one. Knows only what's actually for sale — pathways with no live
 * listings simply don't appear, which is very much in character.
 *
 * <p>Ingredients get a shelf of their own. CoI tags them with nothing but
 * {@code circleofimagination:ingredient} — no pathway, no sequence — so they are
 * invisible to a pathway index however many of them are listed.
 *
 * <p>Configured via {@code informant-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.pathway} ({@code %pathway%}), {@code
 * items.ingredients} ({@code %count%}), {@code items.nothing} (shown when no
 * beyonder goods are listed at all).
 */
public class InformantSearchGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CONTENT_START = 9;

    private final MarketServices services;

    public InformantSearchGUI(MarketServices services) {
        this.services = services;
    }

    public void open(Player player) {
        services.db().submitThenMain(ListingDao::coiIndex,
                index -> render(player, index),
                error -> player.sendMessage(MM.deserialize(services.config().message("market-error",
                        "<red>The market ledgers are in disarray. Try again later."))));
    }

    private void render(Player player, ListingDao.CoiIndex index) {
        List<String> pathways = index.pathways();
        ConfigurationSection config = services.config().guiSection("informant-gui");

        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        if (pathways.isEmpty() && index.unaligned() == 0) {
            gui.setItem(13, Item.builder().setItemProvider(
                    MarketBrowseGUI.configItem(config, "items.nothing", player, Material.GLASS_BOTTLE,
                            "<gray>\"ɴᴏᴛʜɪɴɢ ᴍʏsᴛɪᴄᴀʟ ᴏɴ ᴛʜᴇ sʜᴇʟᴠᴇs ᴛᴏᴅᴀʏ...\"", Map.of())).build());
        }

        int slot = CONTENT_START;
        for (String pathway : pathways) {
            if (slot >= 36) break;
            Map<String, String> extras = Map.of("%pathway%", GuiUtil.prettify(pathway));
            var icon = MarketBrowseGUI.configItem(config, "items.pathway", player,
                    Material.ENCHANTED_BOOK, "<dark_purple>%pathway%", extras);
            gui.setItem(slot, Item.builder().setItemProvider(icon)
                    .addClickHandler(_ -> new MarketBrowseGUI(services, () -> open(player))
                            .open(player, MarketBrowseGUI.Filter.pathway(pathway, null), ListingDao.Sort.NEWEST, 0))
                    .build());
            slot++;
        }

        // Ingredients carry no pathway of their own, so they can never appear above —
        // without their own shelf a stall full of them reads as an empty market.
        if (index.unaligned() > 0 && slot < 36) {
            Map<String, String> extras = Map.of("%count%", String.valueOf(index.unaligned()));
            var icon = MarketBrowseGUI.configItem(config, "items.ingredients", player,
                    Material.GLOW_BERRIES, "<dark_purple>ɪɴɢʀᴇᴅɪᴇɴᴛs <dark_gray>(%count%)", extras);
            gui.setItem(slot, Item.builder().setItemProvider(icon)
                    .addClickHandler(_ -> new MarketBrowseGUI(services, () -> open(player))
                            .open(player, MarketBrowseGUI.Filter.ingredients(), ListingDao.Sort.NEWEST, 0))
                    .build());
        }

        String titleStr = config != null ? config.getString("title", "The Informant") : "The Informant";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of())))
                .build()
                .open();
    }
}
