package dev.ua.ikeepcalm.wiic.domain.agora.integration;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.EntranceService;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The only class in WIIC that touches the Lands API. Must never be classloaded
 * unless {@code Bukkit.getPluginManager().isPluginEnabled("Lands")} — callers go
 * through {@link EntranceService}, which holds this behind a null check.
 *
 * <p>Main-thread only (Lands' area lookups are not thread-safe by contract).
 */
public class LandsHook {

    /** Minimal land identity used by the entrance registry. */
    public record LandInfo(String id, String name) {}

    private final LandsIntegration api;

    /**
     * @throws NoSuchMethodError if the installed Lands build does not match the API we compiled
     *                           against; the caller disables the hook rather than let the
     *                           mismatch surface on every player interaction
     */
    public LandsHook(WIIC plugin) {
        this.api = LandsIntegration.of(plugin);
        requireApi(Land.class, "getULID");
        requireApi(Area.class, "isTrusted", UUID.class);
    }

    private static void requireApi(Class<?> owner, String method, Class<?>... params) {
        try {
            owner.getMethod(method, params);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(owner.getSimpleName() + "." + method
                    + " missing — WIIC needs Lands 8.x (installed build is incompatible)");
        }
    }

    /** The claimed land at {@code location}, or null in wilderness. */
    public @Nullable LandInfo landAt(Location location) {
        Area area = api.getArea(location);
        if (area == null) return null;
        Land land = area.getLand();
        return new LandInfo(land.getULID().toString(), land.getName());
    }

    /** Whether {@code player} is trusted in the claim at {@code location}. */
    public boolean isTrusted(Player player, Location location) {
        Area area = api.getArea(location);
        return area != null && area.isTrusted(player.getUniqueId());
    }

    /** Whether a land with {@code landId} still exists and still claims {@code location}. */
    public boolean landStillClaims(String landId, Location location) {
        LandInfo info = landAt(location);
        return info != null && info.id().equals(landId);
    }
}
