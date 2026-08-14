package dev.ua.ikeepcalm.wiic.domain.agora.market.model;

import dev.ua.ikeepcalm.wiic.utils.WorldUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A registered market entrance door. {@code landId} is the Lands land id the
 * entrance belongs to (one per land, DB-unique) — {@code null} marks the
 * permanent admin-placed hub entrance.
 */
public record MarketEntrance(
        UUID id,
        @Nullable String landId,
        String world,
        int x,
        int y,
        int z,
        UUID createdBy,
        long createdAt
) {
    public boolean isHub() {
        return landId == null;
    }

    public @Nullable Location location() {
        World w = WorldUtil.resolve(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    public boolean matches(Location loc) {
        return WorldUtil.matches(loc.getWorld(), world)
                && loc.getBlockX() == x && loc.getBlockY() == y && loc.getBlockZ() == z;
    }
}
