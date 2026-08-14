package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.Listing;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ListingState;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketPurchaseService;
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
import java.util.UUID;

/**
 * Listing detail + purchase confirmation — the only screen that calls
 * {@link MarketPurchaseService#purchase}. Re-reads the listing from the DB on
 * open so the preview is never stale, and copies {@code PurchaseConfirmGUI}'s
 * confirm-arm delay and {@code boolean[] acted} re-entrancy guard.
 *
 * <p>Configured via {@code detail-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.confirm} ({@code %price%}), {@code items.cancel}.
 */
public class ListingDetailGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PREVIEW_SLOT = 13;
    private static final int CONFIRM_SLOT = 29;
    private static final int CANCEL_SLOT = 33;

    private final MarketServices services;
    private final Runnable onBack;

    public ListingDetailGUI(MarketServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player, UUID listingId) {
        services.db().submitThenMain(conn -> ListingDao.findById(conn, listingId), listing -> {
            if (listing == null || listing.state() != ListingState.ACTIVE) {
                player.sendMessage(MM.deserialize(services.config().message("listing-gone",
                        "<red>That offer is no longer on the table.")));
                onBack.run();
                return;
            }
            render(player, listing);
        }, error -> onBack.run());
    }

    private void render(Player player, Listing listing) {
        ConfigurationSection config = services.config().guiSection("detail-gui");

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

        gui.setItem(PREVIEW_SLOT, Item.builder().setItemProvider(buildPreview(listing, player)).build());

        boolean[] acted = {false};
        long armAt = System.currentTimeMillis() + services.config().confirmArmMs();
        String price = MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(listing.price())));

        ItemStack confirm = MarketBrowseGUI.configItem(config, "items.confirm", player,
                Material.LIME_CONCRETE, "<green>ʙᴜʏ ꜰᴏʀ %price%", Map.of("%price%", price));
        gui.setItem(CONFIRM_SLOT, Item.builder().setItemProvider(confirm)
                .addClickHandler(_ -> {
                    if (acted[0] || System.currentTimeMillis() < armAt) return;
                    acted[0] = true;
                    services.purchases().purchase(player, listing.id(), listing.price(),
                            outcome -> handleOutcome(player, listing, outcome));
                })
                .build());

        ItemStack cancel = MarketBrowseGUI.configItem(config, "items.cancel", player,
                Material.RED_CONCRETE, "<red>ᴄᴀɴᴄᴇʟ", Map.of());
        gui.setItem(CANCEL_SLOT, Item.builder().setItemProvider(cancel)
                .addClickHandler(_ -> {
                    if (acted[0]) return;
                    acted[0] = true;
                    onBack.run();
                })
                .build());

        String titleStr = config != null ? config.getString("title", "Inspect offer") : "Inspect offer";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of())))
                .addCloseHandler(_ -> {
                    if (!acted[0]) onBack.run();
                    acted[0] = false;
                })
                .build()
                .open();
    }

    private ItemStack buildPreview(Listing listing, Player viewer) {
        ItemStack preview;
        try {
            preview = ItemStack.deserializeBytes(listing.itemBytes());
        } catch (Exception e) {
            preview = new ItemStack(listing.material(), Math.max(1, listing.amount()));
        }
        String price = MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(listing.price())));
        String expires = MarketBrowseGUI.formatRemaining(listing.expiresAt() - System.currentTimeMillis());
        preview.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);
            lore.add(Component.empty());
            lore.add(line("<dark_gray>──────────────"));
            lore.add(line("<gold>" + price));
            lore.add(line("<gray>sᴏʟᴅ ʙʏ <white>" + listing.sellerName()));
            lore.add(line("<dark_gray>ᴇxᴘɪʀᴇs ɪɴ " + expires));
            // Tells the buyer up front where the goods will land — stash or courier.
            if (services.courier() != null && services.courier().hasContract(viewer.getUniqueId())) {
                lore.add(line(services.config().message("detail-courier-line",
                        "<dark_purple>ʏᴏᴜʀ ᴄᴏᴜʀɪᴇʀ ᴡɪʟʟ ʙʀɪɴɢ ɪᴛ")));
            }
            meta.lore(lore);
        });
        return preview;
    }

    private static Component line(String miniMessage) {
        return MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    private void handleOutcome(Player player, Listing listing, MarketPurchaseService.Outcome outcome) {
        var config = services.config();
        if (outcome.result() == MarketPurchaseService.Result.SUCCESS) {
            services.feedback().dealStruck(player);
        } else {
            services.feedback().refused(player);
        }
        switch (outcome.result()) {
            case SUCCESS -> player.sendMessage(MM.deserialize(
                    config.message(outcome.couriered() ? "purchase-success-courier" : "purchase-success",
                                    outcome.couriered()
                                            ? "<green>Deal struck. Your courier is already on its way with the goods."
                                            : "<green>Deal struck. The goods await you in your market stash — see the Ledger Keeper.")
                            .replace("%price%", MarketBrowseGUI.plain(
                                    CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(outcome.price()))))));
            case ALREADY_IN_PROGRESS -> player.sendMessage(MM.deserialize(config.message("in-progress",
                    "<red>Finish your current dealing first.")));
            case NO_LONGER_AVAILABLE -> player.sendMessage(MM.deserialize(config.message("listing-gone",
                    "<red>That offer is no longer on the table.")));
            case PRICE_CHANGED -> player.sendMessage(MM.deserialize(config.message("price-changed",
                    "<red>The terms shifted while you hesitated — look again.")));
            case SELF_PURCHASE -> player.sendMessage(MM.deserialize(config.message("self-purchase",
                    "<red>Buying your own goods? The fence raises an eyebrow.")));
            case INSUFFICIENT_FUNDS -> player.sendMessage(MM.deserialize(config.message("insufficient-funds",
                    "<red>Your purse is too light for this.")));
            case ERROR -> player.sendMessage(MM.deserialize(config.message("market-error",
                    "<red>The market ledgers are in disarray. Try again later.")));
        }
        onBack.run();
    }
}
