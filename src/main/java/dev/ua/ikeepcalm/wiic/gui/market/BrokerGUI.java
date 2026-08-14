package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.db.DailyCounterDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Fence's counter (Broker NPC): mirrors the player's listable inventory items
 * (same mirror-then-{@code removeItem} custody model as {@code VaultGUI} — items
 * never leave the inventory until the final confirm in {@link ListingPriceGUI}),
 * plus the entry to {@link MyListingsGUI}. Shows today's listing allowance.
 *
 * <p>Configured via {@code broker-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.my-listings}, {@code items.allowance}
 * ({@code %used% %limit% %active% %max-active%}).
 */
public class BrokerGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CONTENT_START = 9;
    private static final int CONTENT_SIZE = 27;
    private static final int MY_LISTINGS_SLOT = 4;
    private static final int ALLOWANCE_SLOT = 8;

    private final MarketServices services;
    private final @Nullable String plotId;

    public BrokerGUI(MarketServices services, @Nullable String plotId) {
        this.services = services;
        this.plotId = plotId;
    }

    public void open(Player player) {
        services.db().submitThenMain(conn -> new int[]{
                DailyCounterDao.listingsCreatedToday(conn, player.getUniqueId()),
                ListingDao.countActiveBySeller(conn, player.getUniqueId())
        }, counts -> render(player, counts[0], counts[1]), error -> render(player, -1, -1));
    }

    private void render(Player player, int usedToday, int active) {
        ConfigurationSection config = services.config().guiSection("broker-gui");

        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        gui.setItem(MY_LISTINGS_SLOT, MarketBrowseGUI.configButton(config, "items.my-listings", player,
                Material.WRITABLE_BOOK, "<gold>ᴍʏ ʟɪsᴛɪɴɢs",
                () -> new MyListingsGUI(services, () -> open(player)).open(player)));

        Map<String, String> allowanceExtras = Map.of(
                "%used%", usedToday < 0 ? "?" : String.valueOf(usedToday),
                "%limit%", String.valueOf(services.config().dailyListingLimit()),
                "%active%", active < 0 ? "?" : String.valueOf(active),
                "%max-active%", String.valueOf(services.config().maxActivePerPlayer()));
        gui.setItem(ALLOWANCE_SLOT, Item.builder().setItemProvider(
                MarketBrowseGUI.configItem(config, "items.allowance", player, Material.CLOCK,
                        "<gray>ᴛᴏᴅᴀʏ: <gold>%used%/%limit% <gray>ᴀᴄᴛɪᴠᴇ: <gold>%active%/%max-active%",
                        allowanceExtras)).build());

        // Mirror of the player's listable items — display only, custody stays with the player.
        List<ItemStack> listable = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            if (CoinUtil.isCoin(stack)) continue;
            if (services.inspector().checkDenied(stack) != null) continue;
            listable.add(stack);
        }

        boolean[] navigated = {false};
        int slot = CONTENT_START;
        for (ItemStack stack : listable) {
            if (slot >= CONTENT_START + CONTENT_SIZE) break;
            final ItemStack item = stack;
            gui.setItem(slot, Item.builder().setItemProvider(item.clone())
                    .addClickHandler(_ -> {
                        if (navigated[0]) return;
                        navigated[0] = true;
                        new ListingPriceGUI(services, plotId, () -> open(player)).open(player, item);
                    })
                    .build());
            slot++;
        }

        String titleStr = config != null ? config.getString("title", "The Fence") : "The Fence";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of())))
                .build()
                .open();
    }
}
