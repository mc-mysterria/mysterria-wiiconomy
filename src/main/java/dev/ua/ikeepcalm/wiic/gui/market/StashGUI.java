package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
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
 * Stash pickup at the Ledger Keeper: purchases, expired and withdrawn listings.
 * Click an item to claim it, or the claim-all button for the whole page — both go
 * through {@code StashService.claim} (CAS mark, then hand-over, inventory-space
 * aware).
 *
 * <p>Configured via {@code stash-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.back}, {@code items.claim-all}, {@code
 * items.stash-entry.lore} ({@code %source%}).
 */
public class StashGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CONTENT_START = 9;
    private static final int BACK_SLOT = 0;
    private static final int CLAIM_ALL_SLOT = 49;

    private final MarketServices services;
    private final Runnable onBack;

    public StashGUI(MarketServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player) {
        services.stash().listUnclaimed(player, items -> render(player, items));
    }

    private void render(Player player, List<StashItem> items) {
        ConfigurationSection config = services.config().guiSection("stash-gui");

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

        boolean[] navigated = {false};
        List<String> loreTemplate = config != null ? config.getStringList("items.stash-entry.lore") : List.of();
        int slot = CONTENT_START;
        for (StashItem item : items) {
            if (slot >= 45) break;
            gui.setItem(slot, Item.builder().setItemProvider(buildIcon(item, loreTemplate))
                    .addClickHandler(_ -> {
                        if (navigated[0]) return;
                        navigated[0] = true;
                        services.stash().claim(player, List.of(item.id()),
                                (delivered, remaining) -> afterClaim(player, delivered, remaining));
                    })
                    .build());
            slot++;
        }

        if (!items.isEmpty()) {
            List<java.util.UUID> allIds = items.stream().map(StashItem::id).toList();
            gui.setItem(CLAIM_ALL_SLOT, MarketBrowseGUI.configButton(config, "items.claim-all", player,
                    Material.CHEST_MINECART, "<green>ᴄʟᴀɪᴍ ᴀʟʟ",
                    () -> {
                        if (navigated[0]) return;
                        navigated[0] = true;
                        services.stash().claim(player, allIds,
                                (delivered, remaining) -> afterClaim(player, delivered, remaining));
                    }));
        }

        String titleStr = config != null ? config.getString("title", "Your stash") : "Your stash";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of())))
                .build()
                .open();
    }

    private void afterClaim(Player player, int delivered, int remaining) {
        var config = services.config();
        if (delivered > 0) {
            services.feedback().parcelHandedOver(player);
            player.sendMessage(MM.deserialize(config.message("stash-claimed",
                    "<green>The keeper hands over %count% parcel(s).").replace("%count%", String.valueOf(delivered))));
        } else {
            player.sendMessage(MM.deserialize(config.message("stash-nothing",
                    "<gray>Nothing could be handed over — check your inventory space.")));
        }
        if (remaining > 0) {
            player.sendMessage(MM.deserialize(config.message("stash-remaining",
                    "<gray>%count% parcel(s) remain in your stash.").replace("%count%", String.valueOf(remaining))));
        }
        open(player);
    }

    private ItemStack buildIcon(StashItem item, List<String> loreTemplate) {
        ItemStack display;
        try {
            display = ItemStack.deserializeBytes(item.itemBytes());
        } catch (Exception e) {
            display = new ItemStack(item.material(), Math.max(1, item.amount()));
        }
        display.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);
            List<String> template = loreTemplate.isEmpty()
                    ? List.of("", "<dark_gray>ꜰʀᴏᴍ: %source%", "<green>ᴄʟɪᴄᴋ ᴛᴏ ᴄʟᴀɪᴍ")
                    : loreTemplate;
            for (String line : template) {
                String resolved = line.replace("%source%", item.source().toLowerCase());
                lore.add(resolved.isEmpty() ? Component.empty()
                        : MM.deserialize(resolved).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        });
        return display;
    }
}
