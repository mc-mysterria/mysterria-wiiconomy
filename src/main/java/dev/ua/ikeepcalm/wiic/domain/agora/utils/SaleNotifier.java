package dev.ua.ikeepcalm.wiic.domain.agora.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketFeedback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a seller that <em>something</em> of theirs has sold, wherever in the world they
 * happen to be standing — and deliberately refuses to say what, or for how much.
 *
 * <p>That vagueness is the feature. A notification carrying the item and the price turns
 * the Ledger Keeper into a receipt you never need to collect; a rumour that business was
 * done sends the player back underground to find out what and count it. The market keeps
 * its physical-presence rule and still stops feeling like shouting into a void.
 *
 * <p>Sellers with a busy stall would otherwise get a line per sale, so word only reaches
 * anyone once per {@code notifications.cooldown-seconds} — the message says nothing
 * quantitative, so collapsing ten sales into one costs the player nothing.
 */
public class SaleNotifier {

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketFeedback feedback;

    /** seller → when word last reached them. */
    private final Map<UUID, Long> lastNotified = new ConcurrentHashMap<>();

    public SaleNotifier(WIIC plugin, MarketConfig config, MarketFeedback feedback) {
        this.plugin = plugin;
        this.config = config;
        this.feedback = feedback;
    }

    /**
     * Word of a sale reaches {@code sellerUuid} if they are online and haven't heard
     * recently. Main thread.
     */
    public void sold(UUID sellerUuid) {
        if (!config.notificationsEnabled()) return;
        Player seller = Bukkit.getPlayer(sellerUuid);
        if (seller == null) return;

        long now = System.currentTimeMillis();
        Long last = lastNotified.get(sellerUuid);
        if (last != null && now - last < config.notificationCooldownMs()) return;
        lastNotified.put(sellerUuid, now);

        // In the market itself the news arrives as a glance from across the room; out in
        // the world it is the uneasy sense that somewhere your name came up.
        boolean underground = config.isMarketWorld(seller.getWorld());
        String message = underground
                ? config.message("sale-rumour-market",
                        "<dark_gray><i>The Ledger Keeper catches your eye and taps the ledger.</i>")
                : config.message("sale-rumour",
                        "<dark_gray><i>Somewhere beneath the streets, a coin is set aside in your name.</i>");
        seller.sendMessage(MiniMessage.miniMessage().deserialize(message));
        feedback.rumour(seller);
    }

    /**
     * Greets a returning player who has proceeds or parcels waiting. Sales that landed
     * while they were offline produce no rumour at all, so without this the market would
     * simply never mention them.
     */
    public void greetReturning(Player player, int stashCount, long unclaimed) {
        if (!config.notificationsEnabled()) return;
        if (stashCount <= 0 && unclaimed <= 0) return;
        lastNotified.put(player.getUniqueId(), System.currentTimeMillis());
        player.sendMessage(MiniMessage.miniMessage().deserialize(config.message("sale-rumour-return",
                "<dark_gray><i>While you were away, business was done in your name. The Ledger Keeper is waiting.</i>")));
        feedback.rumour(player);
    }

    /** Forgets a departed player's cooldown so the map can't grow without bound. */
    public void forget(UUID player) {
        lastNotified.remove(player);
    }
}
