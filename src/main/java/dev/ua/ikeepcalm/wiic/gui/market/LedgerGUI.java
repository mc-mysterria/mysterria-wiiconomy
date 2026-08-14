package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.LedgerEntry;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.LedgerService;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
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
 * Sale-proceeds claim at the Ledger Keeper. Shows each unclaimed sale (gross, tax,
 * net) and a single claim button that deposits the total — the physical-presence
 * counterpart of an auction house's automatic payout.
 *
 * <p>Configured via {@code ledger-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.back}, {@code items.claim} ({@code %total%}),
 * {@code items.entry} ({@code %net% %tax% %gross%} name/lore).
 */
public class LedgerGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CONTENT_START = 9;
    private static final int BACK_SLOT = 0;
    private static final int CLAIM_SLOT = 49;

    private final MarketServices services;
    private final Runnable onBack;

    public LedgerGUI(MarketServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player) {
        services.ledger().summary(player, summary -> render(player, summary));
    }

    private void render(Player player, LedgerService.Summary summary) {
        ConfigurationSection config = services.config().guiSection("ledger-gui");
        List<LedgerEntry> entries = summary.entries();
        long total = summary.total();

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

        gui.setItem(BACK_SLOT, MarketBrowseGUI.configButton(config, "items.back", player,
                Material.ARROW, "<gray>ʙᴀᴄᴋ", onBack));

        int slot = CONTENT_START;
        for (LedgerEntry entry : entries) {
            if (slot >= 45) break;
            Map<String, String> extras = Map.of(
                    "%net%", MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(entry.net()))),
                    "%tax%", MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(entry.tax()))),
                    "%gross%", MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(entry.gross()))));
            ItemStack icon = MarketBrowseGUI.configItem(config, "items.entry", player,
                    Material.PAPER, "<gold>+%net% <dark_gray>(ᴛᴀx %tax%)", extras);
            gui.setItem(slot, Item.builder().setItemProvider(icon).build());
            slot++;
        }

        if (total > 0) {
            boolean[] acted = {false};
            long armAt = System.currentTimeMillis() + services.config().confirmArmMs();
            Map<String, String> extras = Map.of("%total%",
                    MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(total))));
            ItemStack claim = MarketBrowseGUI.configItem(config, "items.claim", player,
                    Material.GOLD_INGOT, "<green>ᴄʟᴀɪᴍ %total%", extras);
            gui.setItem(CLAIM_SLOT, Item.builder().setItemProvider(claim)
                    .addClickHandler(_ -> {
                        if (acted[0] || System.currentTimeMillis() < armAt) return;
                        acted[0] = true;
                        services.ledger().claim(player, (success, amount) -> {
                            var cfg = services.config();
                            if (success && amount > 0) {
                                services.feedback().coinsCounted(player);
                                player.sendMessage(MM.deserialize(cfg.message("ledger-claimed",
                                                "<green>The keeper counts out %total% into your purse.")
                                        .replace("%total%", MarketBrowseGUI.plain(
                                                CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(amount))))));
                            } else if (!success) {
                                player.sendMessage(MM.deserialize(cfg.message("ledger-claim-failed",
                                        "<red>The keeper's coffers jammed. Try again.")));
                            }
                            open(player);
                        });
                    })
                    .build());
        }

        String titleStr = config != null ? config.getString("title", "The ledger") : "The ledger";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of())))
                .build()
                .open();
    }
}
