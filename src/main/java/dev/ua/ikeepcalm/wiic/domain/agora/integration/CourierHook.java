package dev.ua.ikeepcalm.wiic.domain.agora.integration;

import dev.djecka2k19.undeadPostmans.api.UndeadPostmansApi;
import dev.djecka2k19.undeadPostmans.api.UndeadPostmansProvider;
import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The market's only door to undead-postmans: horn validation, tier resolution, and
 * dispatching a purchase to a courier.
 *
 * <p>This is the single class importing {@code dev.djecka2k19.undeadPostmans.api}
 * (compileOnly, from {@code dev.djecka2k19:undeadpostmans-api}), so it is also the only
 * class that can fail to load when the plugin is absent. {@link #createIfAvailable} is the
 * gate: it checks the plugin is enabled before touching those types and treats a
 * {@link LinkageError} — an installed postmans build older than the API WIIC compiled
 * against — as "unavailable", the same way {@code MarketModule} handles a mismatched
 * Lands. Either way the courier feature disappears and purchases keep landing in the
 * market stash.
 *
 * <p>The API instance is never cached: {@code UndeadPostmansProvider} clears its
 * registration when postmans disables, so every call asks again and a reload of that
 * plugin can't leave the market holding a dead reference.
 */
public class CourierHook {

    private final WIIC plugin;

    private CourierHook(WIIC plugin) {
        this.plugin = plugin;
    }

    /** The hook, or null when postmans is absent or exposes an API WIIC can't drive. */
    public static @Nullable CourierHook createIfAvailable(WIIC plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled("UndeadPostmans")) {
            plugin.getLogger().info("UndeadPostmans not found — market courier delivery is disabled (purchases go to the stash)");
            return null;
        }
        try {
            // First touch of the API types: probes classloading and signature match.
            Optional<UndeadPostmansApi> api = UndeadPostmansProvider.getIfAvailable();
            if (api.isEmpty()) {
                plugin.getLogger().warning("UndeadPostmans is enabled but registered no API — market courier delivery is disabled");
                return null;
            }
            plugin.getLogger().info("Market courier delivery bound to UndeadPostmans " + api.get().getVersion());
            return new CourierHook(plugin);
        } catch (LinkageError e) {
            plugin.getLogger().severe("UndeadPostmans is installed but its API is incompatible with WIIC: "
                    + e.getMessage() + " — market courier delivery is disabled");
            return null;
        }
    }

    /** Whether postmans is still up and able to take deliveries right now. */
    public boolean available() {
        return api().filter(UndeadPostmansApi::isReady).isPresent();
    }

    public boolean isHorn(ItemStack item) {
        return api().map(api -> api.isHorn(item)).orElse(false);
    }

    /**
     * The courier tier {@code player} can summon, resolved from their permissions —
     * postmans keys tiers to the player, not to the horn item, so this must be captured
     * while they are online (see {@code CourierContract}).
     */
    public Optional<String> resolveCourierType(Player player) {
        return api().map(api -> api.resolveCourierType(player));
    }

    public int deliverySeconds(String courierType) {
        return api().map(api -> api.deliverySeconds(courierType)).orElse(0);
    }

    /**
     * Hands {@code item} to a courier for {@code recipient}, attributed to the seller so
     * the delivery message names them. Main thread only.
     *
     * @return false if postmans refused or errored — the caller must keep the goods.
     */
    public boolean dispatch(UUID senderUuid, String senderName, UUID recipientUuid, String recipientName,
                            ItemStack item, String courierType) {
        Optional<UndeadPostmansApi> api = api();
        if (api.isEmpty()) return false;
        try {
            return api.get().sendDelivery(senderUuid, senderName, recipientUuid, recipientName,
                    List.of(item), courierType);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("UndeadPostmans refused a market delivery to " + recipientName + ": " + e);
            return false;
        }
    }

    private Optional<UndeadPostmansApi> api() {
        return UndeadPostmansProvider.getIfAvailable();
    }
}
