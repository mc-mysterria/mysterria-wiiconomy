package dev.ua.ikeepcalm.wiic.domain.agora.plots.listener;

import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketFeedback;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotShop;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotShopService;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Turns clicks into trade at a stall counter.
 *
 * <p>Writing the configured tag on a sign attached to a chest in your own plot builds the
 * counter; right-clicking your own counter with goods in hand stocks it; anyone else's
 * click buys, and always twice — once to be quoted, once to pay.
 *
 * <p>Runs at {@code HIGH} but <em>after</em> the market's protection listener has had its
 * say about the sign itself, so a customer can read and use a counter they have no right
 * to break. Sign blocks are not containers, so {@code MarketProtectionListener} lets the
 * click through while still refusing to open the chest behind it — which is exactly the
 * arrangement a shop needs.
 */
public class PlotShopListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final MarketConfig config;
    private final PlotShopService shops;
    private final MarketFeedback feedback;

    public PlotShopListener(MarketConfig config, PlotShopService shops, MarketFeedback feedback) {
        this.config = config;
        this.shops = shops;
        this.feedback = feedback;
    }

    // -------------------------------------------------------------------------
    // Building the counter
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!shops.enabled() || !config.isMarketWorld(event.getBlock().getWorld())) return;
        if (!PLAIN.serialize(Objects.requireNonNull(event.line(0))).trim().equalsIgnoreCase(config.shopSignTag()))
            return;

        // Cancelled up front, always: the counter renders its own face, so the player's raw
        // text is never what ends up on the block. It also means every rejection below
        // leaves a blank sign they can simply rewrite, instead of a half-built shop.
        event.setCancelled(true);

        Player owner = event.getPlayer();
        Block container = containerBehind(event.getBlock());
        if (container == null) {
            send(owner, "stall-no-container",
                    "<red>\"A counter wants a chest behind it. Put the sign on one.\"");
            return;
        }
        Long price = parsePrice(event.line(1));
        if (price == null) {
            send(owner, "stall-bad-price", "<red>Write the price on the second line — 500, or 3v 12l.");
            return;
        }
        int bundle = parseAmount(event.line(2));

        shops.create(owner, event.getBlock(), container, price, bundle, result -> {
            switch (result) {
                case SUCCESS -> {
                    PlotShop created = shops.at(event.getBlock());
                    if (created != null) shops.renderSign(created);
                    send(owner, "stall-created",
                            "<green>The counter is yours. Right-click it holding what you mean to sell.");
                    feedback.listed(owner);
                }
                case DISABLED -> send(owner, "stall-disabled", "<red>Stall counters are closed for business.");
                case NOT_IN_PLOT -> send(owner, "stall-not-in-plot",
                        "<red>Counters only stand inside a rented stall.");
                case NOT_RENTER -> send(owner, "stall-not-renter",
                        "<red>This stall is not yours to trade from.");
                case NO_CONTAINER -> send(owner, "stall-no-container",
                        "<red>\"A counter wants a chest behind it. Put the sign on one.\"");
                case CONTAINER_OUTSIDE_PLOT -> send(owner, "stall-container-outside",
                        "<red>The chest must stand inside your own stall.");
                case BAD_PRICE -> send(owner, "stall-bad-price",
                        "<red>Write the price on the second line — 500, or 3v 12l.");
                case TOO_MANY -> send(owner, "stall-too-many",
                        "<red>This stall already has as many counters as it can hold.");
                case DUPLICATE -> send(owner, "stall-duplicate", "<red>That sign is already a counter.");
                case ERROR -> send(owner, "stall-error", "<red>The counter refuses to hold. Try again later.");
            }
        });
    }

    /**
     * The block a sign is mounted on: behind a wall sign, beneath a standing one.
     */
    private static @Nullable Block containerBehind(Block signBlock) {
        if (!Tag.ALL_SIGNS.isTagged(signBlock.getType())) return null;
        Block candidate = signBlock.getBlockData() instanceof WallSign wall
                ? signBlock.getRelative(wall.getFacing().getOppositeFace())
                : signBlock.getRelative(BlockFace.DOWN);
        return candidate.getState(false) instanceof org.bukkit.block.Container ? candidate : null;
    }

    // -------------------------------------------------------------------------
    // Using the counter
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (shops.isEmpty() || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !config.isMarketWorld(block.getWorld())) return;
        PlotShop shop = shops.at(block);
        if (shop == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (shop.ownerUuid().equals(player.getUniqueId())) {
            manage(player, shop);
        } else {
            buy(player, shop);
        }
    }

    /**
     * The owner's own click: stock it, close it, or ask it how trade has been.
     */
    private void manage(Player owner, PlotShop shop) {
        ItemStack held = owner.getInventory().getItemInMainHand();
        if (owner.isSneaking()) {
            shops.unbind(shop, ok -> send(owner, ok ? "stall-closed" : "stall-error",
                    ok ? "<gray>The counter is bare again." : "<red>The counter refuses to change."));
            return;
        }
        if (held.getType().isAir()) {
            report(owner, shop);
            return;
        }
        String denied = shops.bind(shop, held, ok -> {
            if (!ok) {
                send(owner, "stall-error", "<red>The counter refuses to change.");
                return;
            }
            feedback.listed(owner);
            owner.sendMessage(MM.deserialize(config.message("stall-stocked",
                            "<green>The counter now sells <white>%item%</white> at "))
                    .replaceText(builder -> builder.matchLiteral("%item%").replacement(itemName(shop, held)))
                    .append(CoinUtil.getFormattedPrice((int) Math.min(Integer.MAX_VALUE, shop.price()))));
        });
        if (denied != null) {
            owner.sendMessage(MM.deserialize(config.message(denied,
                    "<red>The fence looks at it, then at you. \"No.\"")));
            feedback.refused(owner);
        }
    }

    private void report(Player owner, PlotShop shop) {
        int stock = shops.stockOf(shop);
        owner.sendMessage(MM.deserialize(config.message("stall-report-header", "<gold>Your counter")));
        owner.sendMessage(MM.deserialize(config.message("stall-report-goods", "<gray>Selling: <white>%item%")
                .replace("%item%", shop.displayName() == null ? "nothing yet" : shop.displayName())
                .replace("%amount%", String.valueOf(shop.bundle()))));
        owner.sendMessage(MM.deserialize(config.message("stall-report-stock",
                        "<gray>In the chest: <white>%stock%</white>  ·  sold so far: <white>%sold%")
                .replace("%stock%", stock < 0 ? "?" : String.valueOf(stock))
                .replace("%sold%", String.valueOf(shop.soldCount()))));
        owner.sendMessage(MM.deserialize(config.message("stall-report-hint",
                "<dark_gray>Right-click holding goods to restock the sign · sneak+right-click to close it")));
    }

    /**
     * A customer's click: quoted the first time, paid the second.
     */
    private void buy(Player buyer, PlotShop shop) {
        if (!shop.isStocked()) {
            send(buyer, "stall-empty", "<gray>The counter is bare. Nothing is for sale here yet.");
            return;
        }
        if (shops.arm(buyer, shop)) {
            buyer.sendMessage(MM.deserialize(config.message("stall-quote",
                                    "<gray>%item% <dark_gray>x%amount%<gray> — click again to pay ")
                            .replace("%item%", shop.displayName() == null ? "goods" : shop.displayName())
                            .replace("%amount%", String.valueOf(shop.bundle())))
                    .append(CoinUtil.getFormattedPrice((int) Math.min(Integer.MAX_VALUE, shop.price()))));
            feedback.hornChanged(buyer);
            return;
        }
        shops.purchase(buyer, shop, outcome -> {
            switch (outcome.result()) {
                case SUCCESS -> buyer.sendMessage(MM.deserialize(config.message("stall-bought",
                                        "<green>Taken. <white>%item%</white> x%amount% for ")
                                .replace("%item%", outcome.itemName())
                                .replace("%amount%", String.valueOf(outcome.amount())))
                        .append(CoinUtil.getFormattedPrice((int) Math.min(Integer.MAX_VALUE, outcome.price()))));
                case OUT_OF_STOCK -> send(buyer, "stall-out-of-stock",
                        "<gray>The chest is empty. Whatever was here, someone was quicker.");
                case INSUFFICIENT_FUNDS -> send(buyer, "stall-cannot-afford", "<red>You cannot afford that.");
                case SELF_PURCHASE -> send(buyer, "stall-self", "<gray>You already own everything in this chest.");
                case CLOSED -> send(buyer, "stall-gone", "<gray>This stall has changed hands. The counter is dead.");
                case UNSTOCKED -> send(buyer, "stall-empty",
                        "<gray>The counter is bare. Nothing is for sale here yet.");
                case BUSY -> send(buyer, "stall-busy", "<gray>Someone is already at the counter. A moment.");
                case DISABLED, ERROR -> send(buyer, "stall-error",
                        "<red>The counter refuses to trade. Try again later.");
            }
            if (outcome.result() != PlotShopService.BuyResult.SUCCESS) feedback.refused(buyer);
        });
    }

    // -------------------------------------------------------------------------
    // Tearing it down
    // -------------------------------------------------------------------------

    /**
     * Breaking the sign or the chest behind it retires the counter. The break itself is
     * left to {@code MarketProtectionListener} to allow or refuse — this only makes sure
     * the registry never points at rubble.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (shops.isEmpty() || !config.isMarketWorld(event.getBlock().getWorld())) return;
        for (PlotShop shop : shops.touching(event.getBlock())) {
            shops.remove(shop, "broken by " + event.getPlayer().getName());
        }
    }

    // -------------------------------------------------------------------------
    // Parsing what the owner wrote
    // -------------------------------------------------------------------------

    /**
     * A price line: a plain coppet count, or shorthand like {@code 3v 12l 5c}.
     */
    private static @Nullable Long parsePrice(@Nullable Component line) {
        if (line == null) return null;
        String raw = PLAIN.serialize(line).trim().toLowerCase(Locale.ROOT).replace(",", "");
        if (raw.isEmpty()) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            // fall through to the shorthand form
        }
        long total = 0;
        boolean matched = false;
        for (String part : raw.split("\\s+")) {
            if (part.isEmpty()) continue;
            char unit = part.charAt(part.length() - 1);
            long multiplier = switch (unit) {
                case 'v', 'в' -> 4096L;
                case 'l', 'л' -> 64L;
                case 'c', 'к' -> 1L;
                default -> 0L;
            };
            if (multiplier == 0) return null;
            try {
                total += Long.parseLong(part.substring(0, part.length() - 1)) * multiplier;
                matched = true;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return matched && total > 0 ? total : null;
    }

    /**
     * How many items one purchase hands over. Blank means one.
     */
    private static int parseAmount(@Nullable Component line) {
        if (line == null) return 1;
        String raw = PLAIN.serialize(line).trim();
        if (raw.isEmpty()) return 1;
        try {
            return Math.clamp(Integer.parseInt(raw), 1, 64);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static Component itemName(PlotShop shop, ItemStack held) {
        return shop.displayName() != null
                ? Component.text(shop.displayName())
                : held.displayName();
    }

    private void send(Player player, String key, String def) {
        player.sendMessage(MM.deserialize(config.message(key, def)));
    }
}
