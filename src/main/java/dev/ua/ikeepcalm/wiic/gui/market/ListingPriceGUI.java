package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ItemType;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.ListingService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.PriceGuide;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
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
 * Price selection + final listing confirmation. The custody-critical screen: the
 * item stays in the player's inventory until the confirm click, which does the
 * clone-then-{@code removeItem} dance with the "not removed → abort" guard from
 * {@code VaultGUI}, and only then hands the removed snapshot to
 * {@link ListingService#createListing}. Any non-success outcome returns the item
 * via {@code ItemUtil.giveOrDrop}.
 *
 * <p>Configured via {@code price-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.price-display} ({@code %price% %fee%}),
 * {@code items.confirm}, {@code items.cancel}.
 */
public class ListingPriceGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PRICE_SLOT = 4;
    private static final int PREVIEW_SLOT = 13;
    private static final int CONFIRM_SLOT = 21;
    private static final int CANCEL_SLOT = 23;
    /** Step sizes as a fraction of the opening estimate, finest first. */
    private static final double[] STEP_FRACTIONS = {0.01, 0.05, 0.25, 1.0};

    private final MarketServices services;
    private final @Nullable String plotId;
    private final Runnable onBack;

    public ListingPriceGUI(MarketServices services, @Nullable String plotId, Runnable onBack) {
        this.services = services;
        this.plotId = plotId;
        this.onBack = onBack;
    }

    /** Asks the Fence what the goods are worth, then opens on that number. */
    public void open(Player player, ItemStack item) {
        services.prices().suggest(item, suggestion -> render(player, item, suggestion));
    }

    private void render(Player player, ItemStack item, PriceGuide.Suggestion suggestion) {
        // The appraisal is a DB read, so the player had a tick or two to put the goods
        // down. `item` mirrors the inventory slot, so it may be air by now.
        if (item.getType().isAir() || item.getAmount() <= 0) {
            player.sendMessage(MM.deserialize(services.config().message("listing-item-missing",
                    "<red>The goods slipped out of your hands. Nothing was listed.")));
            onBack.run();
            return;
        }
        ConfigurationSection config = services.config().guiSection("price-gui");
        // Single mutable price for the lifetime of the window: the steppers edit this
        // and repaint the display slot in place. Re-opening the window per click would
        // fire this window's own close handler and bounce the player back to the broker.
        long[] price = {Math.clamp(suggestion.total(),
                services.config().minPrice(), services.config().maxPrice())};

        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        renderPrice(gui, config, player, price[0]);

        gui.setItem(PREVIEW_SLOT, Item.builder().setItemProvider(appraisedPreview(item, suggestion)).build());

        // Steppers, coarsest outward: [-big .. -small] [preview] [+small .. +big].
        long[] steps = stepsFor(price[0]);
        for (int i = 0; i < steps.length; i++) {
            gui.setItem(9 + i, stepperButton(gui, config, player, price, -steps[steps.length - 1 - i]));
            gui.setItem(14 + i, stepperButton(gui, config, player, price, steps[i]));
        }

        boolean[] acted = {false};
        long armAt = System.currentTimeMillis() + services.config().confirmArmMs();

        ItemStack confirm = MarketBrowseGUI.configItem(config, "items.confirm", player,
                Material.LIME_CONCRETE, "<green>ʟɪsᴛ ɪᴛ", Map.of());
        gui.setItem(CONFIRM_SLOT, Item.builder().setItemProvider(confirm)
                .addClickHandler(_ -> {
                    if (acted[0] || System.currentTimeMillis() < armAt) return;
                    acted[0] = true;
                    confirmListing(player, item, price[0]);
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

        String titleStr = config != null ? config.getString("title", "Name your price") : "Name your price";
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

    /**
     * The item as the Fence sees it: the goods themselves, plus what he reckons they are
     * worth and why. Saying where the number came from matters — "eleven of these have
     * changed hands" is a fact about the market, while a sequence estimate is an opinion,
     * and a seller deserves to know which one they are looking at.
     */
    private ItemStack appraisedPreview(ItemStack item, PriceGuide.Suggestion suggestion) {
        ItemStack display = item.clone();
        var facts = suggestion.facts();
        String worth = MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(
                MarketBrowseGUI.clampToInt(suggestion.total())));

        display.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);
            lore.add(Component.empty());
            lore.add(line("<dark_gray>──────────────"));
            lore.add(line(services.config().message("appraisal-header", "<gray>ᴛʜᴇ ꜰᴇɴᴄᴇ ʀᴇᴄᴋᴏɴs: <gold>%worth%")
                    .replace("%worth%", worth)));

            switch (suggestion.basis()) {
                case OBSERVED -> lore.add(line(services.config().message("appraisal-observed",
                                "<dark_gray>\"%count% have crossed my counter lately.\"")
                        .replace("%count%", String.valueOf(suggestion.sampled()))));
                case BEYONDER -> {
                    if (facts.servedSequence() != null) {
                        lore.add(line(services.config().message("appraisal-sequence",
                                        "<dark_purple>\"ꜱᴇǫᴜᴇɴᴄᴇ %sequence% ɢᴏᴏᴅꜱ. ɪ ᴋɴᴏᴡ ᴡʜᴀᴛ ᴛʜᴀᴛ ᴍᴇᴀɴꜱ.\"")
                                .replace("%sequence%", String.valueOf(facts.servedSequence()))));
                    }
                    if (facts.kind() == ItemType.INGREDIENT && facts.servedSequence() != null) {
                        lore.add(line(services.config().message("appraisal-ingredient",
                                "<dark_gray>\"ɴᴏᴛ ᴍᴜᴄʜ ᴀʟᴏɴᴇ. ᴘᴀʀᴛ ᴏꜰ ꜱᴏᴍᴇᴛʜɪɴɢ ᴍᴜᴄʜ ᴍᴏʀᴇ.\"")));
                    }
                }
                case SHOP -> lore.add(line(services.config().message("appraisal-mundane",
                        "<dark_gray>\"ᴏʀᴅɪɴᴀʀʏ ꜱᴛᴏᴄᴋ. ɪ ᴋɴᴏᴡ ᴛʜᴇ ɢᴏɪɴɢ ʀᴀᴛᴇ.\"")));
                case NONE -> lore.add(line(services.config().message("appraisal-unknown",
                        "<dark_gray>\"ɴᴇᴠᴇʀ ꜱᴇᴇɴ ɪᴛꜱ ʟɪᴋᴇ. ɴᴀᴍᴇ ʏᴏᴜʀ ᴏᴡɴ ᴘʀɪᴄᴇ.\"")));
            }
            meta.lore(lore);
        });
        return display;
    }

    private static Component line(String miniMessage) {
        return MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    /** Repaints the asking-price/fee slot of an already-open window. */
    private void renderPrice(Gui gui, @Nullable ConfigurationSection config, Player player, long price) {
        Map<String, String> extras = Map.of(
                "%price%", MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(price))),
                "%fee%", MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(
                        MarketBrowseGUI.clampToInt(services.config().listingFee(price)))));
        gui.setItem(PRICE_SLOT, Item.builder().setItemProvider(
                MarketBrowseGUI.configItem(config, "items.price-display", player, Material.GOLD_NUGGET,
                        "<gold>ᴀsᴋɪɴɢ: %price% <dark_gray>(ꜰᴇᴇ %fee%)", extras)).build());
    }

    /**
     * Step sizes scaled to what the goods are actually worth. A fixed ladder can't serve
     * both a stack of filler ingredients and a Sequence 4 characteristic — +64 is the
     * whole price of one and a rounding error on the other.
     */
    private static long[] stepsFor(long anchor) {
        long[] steps = new long[STEP_FRACTIONS.length];
        long previous = 0;
        for (int i = 0; i < STEP_FRACTIONS.length; i++) {
            // Strictly increasing, so two buttons never do the same thing on cheap goods.
            steps[i] = Math.max(previous + 1, roundToNice(Math.round(anchor * STEP_FRACTIONS[i])));
            previous = steps[i];
        }
        return steps;
    }

    /** Rounds to 1/2/5 x a power of ten, so the buttons read as prices and not as noise. */
    private static long roundToNice(long value) {
        if (value <= 1) return 1;
        long magnitude = 1;
        while (magnitude * 10 <= value) magnitude *= 10;
        long leading = value / magnitude;
        long snapped = leading >= 5 ? 5 : leading >= 2 ? 2 : 1;
        return snapped * magnitude;
    }

    private Item stepperButton(Gui gui, @Nullable ConfigurationSection config, Player player,
                               long[] price, long delta) {
        String label = (delta > 0 ? "<green>+" : "<red>-")
                + MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(
                        MarketBrowseGUI.clampToInt(Math.abs(delta))));
        return Item.builder()
                .setItemProvider(MarketBrowseGUI.plainItem(delta > 0 ? Material.LIME_DYE : Material.RED_DYE, label))
                .addClickHandler(_ -> {
                    long updated = Math.clamp(price[0] + delta,
                            services.config().minPrice(), services.config().maxPrice());
                    if (updated == price[0]) return;
                    price[0] = updated;
                    renderPrice(gui, config, player, updated);
                })
                .build();
    }

    private void confirmListing(Player player, ItemStack item, long price) {
        // `item` is a live mirror of the inventory slot the Broker screen read it from. The
        // player can still act on their own inventory while this window is open, so by now
        // the slot may hold air, or something else entirely.
        ItemStack snapshot = item.clone();
        if (snapshot.getType().isAir() || snapshot.getAmount() <= 0) {
            player.sendMessage(MM.deserialize(services.config().message("listing-item-missing",
                    "<red>The goods slipped out of your hands. Nothing was listed.")));
            onBack.run();
            return;
        }
        if (!player.getInventory().containsAtLeast(snapshot, snapshot.getAmount())) {
            player.sendMessage(MM.deserialize(services.config().message("listing-item-missing",
                    "<red>The goods slipped out of your hands. Nothing was listed.")));
            onBack.run();
            return;
        }
        // Hand removeItem a detached copy: it decrements the stack it is given, and mutating
        // the live mirror mid-removal is how slots get corrupted.
        Map<Integer, ItemStack> notRemoved = player.getInventory().removeItem(snapshot.clone());
        if (!notRemoved.isEmpty()) {
            // removeItem is destructive on a partial match — it empties the slots it found
            // before reporting the shortfall. Give back whatever it managed to take.
            int missing = notRemoved.values().stream().mapToInt(ItemStack::getAmount).sum();
            int taken = snapshot.getAmount() - missing;
            if (taken > 0) {
                ItemStack recovered = snapshot.clone();
                recovered.setAmount(taken);
                ItemUtil.giveOrDrop(player, recovered);
            }
            player.sendMessage(MM.deserialize(services.config().message("listing-item-missing",
                    "<red>The goods slipped out of your hands. Nothing was listed.")));
            onBack.run();
            return;
        }

        services.listings().createListing(player, snapshot, price, plotId, outcome -> {
            var config = services.config();
            if (outcome.result() == ListingService.Result.SUCCESS) {
                services.feedback().listed(player);
            } else {
                services.feedback().refused(player);
            }
            switch (outcome.result()) {
                case SUCCESS -> player.sendMessage(MM.deserialize(config.message("listing-created",
                                "<green>The fence takes your goods with a nod. Fee: %fee%.")
                        .replace("%fee%", MarketBrowseGUI.plain(
                                CoinUtil.getFormattedPrice(MarketBrowseGUI.clampToInt(outcome.fee()))))));
                case ALREADY_IN_PROGRESS -> returnItem(player, snapshot, "in-progress",
                        "<red>Finish your current dealing first.");
                case ITEM_DENIED -> returnItem(player, snapshot,
                        outcome.denyMessageKey() != null ? outcome.denyMessageKey() : "listing-denied",
                        "<red>The fence refuses to touch this.");
                case PRICE_OUT_OF_BOUNDS -> returnItem(player, snapshot, "listing-bad-price",
                        "<red>No one would pay that. Pick a saner price.");
                case DAILY_LIMIT -> returnItem(player, snapshot, "listing-daily-limit",
                        "<red>The fence waves you off — come back tomorrow.");
                case MAX_ACTIVE -> returnItem(player, snapshot, "listing-max-active",
                        "<red>Your stall is full. Wait for something to sell.");
                case INSUFFICIENT_FEE -> returnItem(player, snapshot, "listing-fee-unpaid",
                        "<red>You can't afford the listing fee.");
                case ERROR -> returnItem(player, snapshot, "market-error",
                        "<red>The market ledgers are in disarray. Try again later.");
            }
            onBack.run();
        });
    }

    private void returnItem(Player player, ItemStack snapshot, String messageKey, String def) {
        ItemUtil.giveOrDrop(player, snapshot);
        player.sendMessage(MM.deserialize(services.config().message(messageKey, def)));
    }
}
