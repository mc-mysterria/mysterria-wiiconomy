package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.Listing;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ListingState;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The seller's own active listings (reached from the Broker). Clicking a listing
 * cancels it — the item returns to the seller's stash, not their inventory, in
 * the same DB transaction ({@code ListingService.cancelListing}).
 *
 * <p>Configured via {@code my-listings-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.back}, {@code items.listing.lore}
 * ({@code %price% %expires%}).
 */
public class MyListingsGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CONTENT_START = 9;
    private static final int BACK_SLOT = 0;

    private final MarketServices services;
    private final Runnable onBack;

    public MyListingsGUI(MarketServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player) {
        services.db().submitThenMain(conn -> ListingDao.bySeller(conn, player.getUniqueId()),
                listings -> render(player, listings),
                error -> onBack.run());
    }

    private void render(Player player, List<Listing> listings) {
        ConfigurationSection config = services.config().guiSection("my-listings-gui");

        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        gui.setItem(BACK_SLOT, MarketBrowseGUI.configButton(config, "items.back", player,
                Material.ARROW, "<gray>ʙᴀᴄᴋ", onBack));

        boolean[] navigated = {false};
        List<String> loreTemplate = config != null ? config.getStringList("items.listing.lore") : List.of();
        int slot = CONTENT_START;
        for (Listing listing : listings) {
            if (slot >= 45) break;
            ItemStack display = buildIcon(listing, loreTemplate);
            gui.setItem(slot, Item.builder().setItemProvider(display)
                    .addClickHandler(_ -> {
                        if (navigated[0] || listing.state() != ListingState.ACTIVE) return;
                        navigated[0] = true;
                        services.listings().cancelListing(player, listing.id(), success -> {
                            player.sendMessage(MM.deserialize(services.config().message(
                                    success ? "listing-cancelled" : "listing-cancel-failed",
                                    success ? "<gray>Withdrawn. The goods wait in your stash at the Ledger Keeper."
                                            : "<red>Could not withdraw that listing.")));
                            open(player);
                        });
                    })
                    .build());
            slot++;
        }

        String titleStr = config != null ? config.getString("title", "Your listings") : "Your listings";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of())))
                .build()
                .open();
    }

    private ItemStack buildIcon(Listing listing, List<String> loreTemplate) {
        ItemStack display = new ItemStack(listing.material(),
                Math.clamp(listing.amount(), 1, listing.material().getMaxStackSize()));
        String price = MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(listing.price())));
        String expires = MarketBrowseGUI.formatRemaining(listing.expiresAt() - System.currentTimeMillis());
        display.editMeta(meta -> {
            if (listing.displayName() != null && !listing.displayName().isEmpty()) {
                meta.displayName(Component.text(listing.displayName()).decoration(TextDecoration.ITALIC, false));
            }
            List<Component> lore = new ArrayList<>();
            List<String> template = loreTemplate.isEmpty()
                    ? List.of("", "<gold>%price%", "<dark_gray>ᴇxᴘɪʀᴇs ɪɴ %expires%", "", "<red>ᴄʟɪᴄᴋ ᴛᴏ ᴡɪᴛʜᴅʀᴀᴡ")
                    : loreTemplate;
            for (String line : template) {
                String resolved = line.replace("%price%", price).replace("%expires%", expires);
                lore.add(resolved.isEmpty() ? Component.empty()
                        : MM.deserialize(resolved).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        });
        return display;
    }
}
