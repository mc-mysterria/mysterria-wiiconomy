package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRegion;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRental;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotService;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
 * The Plot Warden's board: every configured prestige plot with its status, and the
 * rent / upkeep / hand-back actions for the viewer's own stall.
 *
 * <p>Three screens, each its own window (list → rent confirmation → manage). None of
 * them installs a close handler: these screens re-open one another, and a
 * back-on-close handler would fire on every hop and bounce the player out.
 *
 * <p>Configured via {@code plot-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.back}, {@code items.confirm}, {@code items.cancel},
 * {@code items.extend}, {@code items.release}, and the {@code lore.available} /
 * {@code lore.yours} / {@code lore.taken} templates ({@code %plot% %price% %period%
 * %renter% %expires%}).
 */
public class PlotManageGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 44;
    private static final int BACK_SLOT = 0;
    private static final int PREVIEW_SLOT = 13;
    private static final int CONFIRM_SLOT = 29;
    private static final int CANCEL_SLOT = 33;
    private static final int EXTEND_SLOT = 29;
    private static final int RELEASE_SLOT = 33;

    private final MarketServices services;
    private final Runnable onBack;

    public PlotManageGUI(MarketServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    // -------------------------------------------------------------------------
    // Plot list
    // -------------------------------------------------------------------------

    public void open(Player player) {
        PlotService plots = services.plots();
        ConfigurationSection config = services.config().guiSection("plot-gui");

        if (!plots.enabled()) {
            player.sendMessage(MM.deserialize(services.config().message("plots-disabled",
                    "<gray>The warden has no stalls to let.")));
            onBack.run();
            return;
        }

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
        int slot = CONTENT_START;
        for (PlotRegion region : plots.allRegions()) {
            if (slot > CONTENT_END) break;
            PlotRental rental = plots.rental(region.id());
            boolean mine = rental != null && rental.renterUuid().equals(player.getUniqueId());
            gui.setItem(slot, Item.builder().setItemProvider(buildIcon(config, region, rental, mine))
                    .addClickHandler(_ -> {
                        if (navigated[0]) return;
                        navigated[0] = true;
                        if (mine) {
                            openManage(player, region);
                        } else if (rental == null) {
                            openRentConfirm(player, region);
                        } else {
                            navigated[0] = false;
                            player.sendMessage(MM.deserialize(services.config().message("plot-taken",
                                            "<red>That stall belongs to %renter%.")
                                    .replace("%renter%", rental.renterName())));
                        }
                    })
                    .build());
            slot++;
        }

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player,
                        config != null ? config.getString("title", "Stalls to let") : "Stalls to let", Map.of())))
                .build()
                .open();
    }

    // -------------------------------------------------------------------------
    // Rent confirmation
    // -------------------------------------------------------------------------

    private void openRentConfirm(Player player, PlotRegion region) {
        ConfigurationSection config = services.config().guiSection("plot-gui");
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

        gui.setItem(PREVIEW_SLOT, Item.builder()
                .setItemProvider(buildIcon(config, region, null, false)).build());

        boolean[] acted = {false};
        long armAt = System.currentTimeMillis() + services.config().confirmArmMs();
        Map<String, String> extras = rentExtras(region, null);

        gui.setItem(CONFIRM_SLOT, Item.builder()
                .setItemProvider(MarketBrowseGUI.configItem(config, "items.confirm", player,
                        Material.LIME_CONCRETE, "<green>ʀᴇɴᴛ ꜰᴏʀ %price%", extras))
                .addClickHandler(_ -> {
                    if (acted[0] || System.currentTimeMillis() < armAt) return;
                    acted[0] = true;
                    services.plots().rent(player, region.id(), result -> {
                        player.sendMessage(MM.deserialize(rentMessage(result, region)));
                        open(player);
                    });
                })
                .build());

        gui.setItem(CANCEL_SLOT, Item.builder()
                .setItemProvider(MarketBrowseGUI.configItem(config, "items.cancel", player,
                        Material.RED_CONCRETE, "<red>ᴄᴀɴᴄᴇʟ", Map.of()))
                .addClickHandler(_ -> {
                    if (acted[0]) return;
                    acted[0] = true;
                    open(player);
                })
                .build());

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player,
                        config != null ? config.getString("rent-title", "Take the stall?") : "Take the stall?",
                        extras)))
                .build()
                .open();
    }

    // -------------------------------------------------------------------------
    // Manage own plot
    // -------------------------------------------------------------------------

    private void openManage(Player player, PlotRegion region) {
        ConfigurationSection config = services.config().guiSection("plot-gui");
        PlotRental rental = services.plots().rental(region.id());
        if (rental == null || !rental.renterUuid().equals(player.getUniqueId())) {
            open(player);
            return;
        }

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
                Material.ARROW, "<gray>ʙᴀᴄᴋ", () -> open(player)));
        gui.setItem(PREVIEW_SLOT, Item.builder()
                .setItemProvider(buildIcon(config, region, rental, true)).build());

        boolean[] acted = {false};
        long armAt = System.currentTimeMillis() + services.config().confirmArmMs();
        Map<String, String> extras = rentExtras(region, rental);

        gui.setItem(EXTEND_SLOT, Item.builder()
                .setItemProvider(MarketBrowseGUI.configItem(config, "items.extend", player,
                        Material.EMERALD, "<green>ᴘᴀʏ ᴜᴘᴋᴇᴇᴘ <gray>(%price%)", extras))
                .addClickHandler(_ -> {
                    if (acted[0] || System.currentTimeMillis() < armAt) return;
                    acted[0] = true;
                    services.plots().extend(player, region.id(), result -> {
                        player.sendMessage(MM.deserialize(extendMessage(result, region)));
                        openManage(player, region);
                    });
                })
                .build());

        gui.setItem(RELEASE_SLOT, Item.builder()
                .setItemProvider(MarketBrowseGUI.configItem(config, "items.release", player,
                        Material.BARRIER, "<red>ɢɪᴠᴇ ᴜᴘ ᴛʜᴇ sᴛᴀʟʟ", extras))
                .addClickHandler(_ -> {
                    if (acted[0] || System.currentTimeMillis() < armAt) return;
                    acted[0] = true;
                    services.plots().release(player, region.id(), success -> {
                        player.sendMessage(MM.deserialize(services.config().message(
                                success ? "plot-released" : "plot-release-failed",
                                success ? "<gray>The warden takes back the keys. Anything you left is in your stash."
                                        : "<red>The warden is still settling that stall.")));
                        open(player);
                    });
                })
                .build());

        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player,
                        config != null ? config.getString("manage-title", "Your stall") : "Your stall", extras)))
                .build()
                .open();
    }

    // -------------------------------------------------------------------------
    // Rendering helpers
    // -------------------------------------------------------------------------

    private ItemStack buildIcon(@Nullable ConfigurationSection config, PlotRegion region,
                                @Nullable PlotRental rental, boolean mine) {
        ItemStack display = new ItemStack(region.icon());
        Map<String, String> extras = rentExtras(region, rental);
        String loreKey = rental == null ? "lore.available" : (mine ? "lore.yours" : "lore.taken");
        List<String> template = config != null ? config.getStringList(loreKey) : List.of();
        if (template.isEmpty()) template = defaultLore(rental, mine);

        List<String> resolved = template;
        display.editMeta(meta -> {
            meta.displayName(MM.deserialize("<gold>" + region.displayName())
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (String line : resolved) {
                String text = line;
                for (Map.Entry<String, String> entry : extras.entrySet()) {
                    text = text.replace(entry.getKey(), entry.getValue());
                }
                lore.add(text.isEmpty() ? Component.empty()
                        : MM.deserialize(text).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        });
        return display;
    }

    private static List<String> defaultLore(@Nullable PlotRental rental, boolean mine) {
        if (rental == null) {
            return List.of("", "<green>ᴀᴠᴀɪʟᴀʙʟᴇ", "<gray>%price% <dark_gray>/ %period%", "", "<dark_gray>ᴄʟɪᴄᴋ ᴛᴏ ʀᴇɴᴛ");
        }
        if (mine) {
            return List.of("", "<gold>ʏᴏᴜʀ sᴛᴀʟʟ", "<gray>ᴘᴀɪᴅ ꜰᴏʀ %expires%", "", "<dark_gray>ᴄʟɪᴄᴋ ᴛᴏ ᴍᴀɴᴀɢᴇ");
        }
        return List.of("", "<red>ᴛᴀᴋᴇɴ", "<gray>ʀᴇɴᴛᴇᴅ ʙʏ <white>%renter%");
    }

    private Map<String, String> rentExtras(PlotRegion region, @Nullable PlotRental rental) {
        long price = services.config().plotRentPrice();
        String expires = rental != null
                ? MarketBrowseGUI.formatRemaining(rental.paidUntil() - System.currentTimeMillis())
                : "-";
        return Map.of(
                "%plot%", region.displayName(),
                "%price%", MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(price))),
                "%period%", services.config().raw().getLong("plots.period-days", 7) + "d",
                "%renter%", rental != null ? rental.renterName() : "-",
                "%expires%", expires);
    }

    private String rentMessage(PlotService.RentResult result, PlotRegion region) {
        return switch (result) {
            case SUCCESS -> services.config().message("plot-rented",
                            "<green>The warden hands you the keys to %plot%.")
                    .replace("%plot%", region.displayName());
            case ALREADY_RENTED -> services.config().message("plot-taken", "<red>That stall belongs to someone else.");
            case MAX_PLOTS -> services.config().message("plot-max", "<red>One stall is all the warden will let you hold.");
            case INSUFFICIENT_FUNDS -> services.config().message("plot-poor", "<red>Your purse is too light for the rent.");
            case IN_PROGRESS -> services.config().message("in-progress", "<red>Finish your current dealing first.");
            case DISABLED, UNKNOWN_PLOT -> services.config().message("plots-disabled",
                    "<gray>The warden has no stalls to let.");
            case ERROR -> services.config().message("market-error",
                    "<red>The market ledgers are in disarray. Try again later.");
        };
    }

    private String extendMessage(PlotService.RentResult result, PlotRegion region) {
        return switch (result) {
            case SUCCESS -> services.config().message("plot-extended",
                            "<green>Upkeep paid. %plot% stays yours.")
                    .replace("%plot%", region.displayName());
            case INSUFFICIENT_FUNDS -> services.config().message("plot-poor", "<red>Your purse is too light for the rent.");
            case IN_PROGRESS -> services.config().message("in-progress", "<red>Finish your current dealing first.");
            default -> services.config().message("market-error",
                    "<red>The market ledgers are in disarray. Try again later.");
        };
    }
}
