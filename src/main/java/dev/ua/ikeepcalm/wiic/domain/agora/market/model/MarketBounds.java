package dev.ua.ikeepcalm.wiic.domain.agora.market.model;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The cuboid a visitor to the Underground Market is kept inside — the outer envelope of
 * the built area, not its walls. Set by an admin with {@code /wiicmarket bounds set}.
 *
 * <p>This is the last line of the containment: whatever ability, plugin or glitch moves a
 * player, if they end up outside this box they are put back. It exists because the market
 * is a hand-built room floating in an otherwise empty world — a single step past the
 * furthest wall is a fall into the void, and no amount of event-blocking can enumerate
 * every way a player might get there.
 *
 * <p>Draw it generously: a few blocks of slack past the outermost structure costs nothing,
 * while a box drawn tight to the walls will shove players around for standing in a doorway.
 */
public record MarketBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /**
     * Parses {@code containment.bounds}, or null when it hasn't been defined yet (in which
     * case only the void floor is enforced).
     */
    public static @Nullable MarketBounds fromConfig(@Nullable ConfigurationSection section) {
        if (section == null) return null;
        List<Integer> min = section.getIntegerList("min");
        List<Integer> max = section.getIntegerList("max");
        if (min.size() < 3 || max.size() < 3) return null;
        return new MarketBounds(
                Math.min(min.get(0), max.get(0)), Math.min(min.get(1), max.get(1)), Math.min(min.get(2), max.get(2)),
                Math.max(min.get(0), max.get(0)), Math.max(min.get(1), max.get(1)), Math.max(min.get(2), max.get(2)));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * Block-granular containment. A player's hitbox is narrower than a block, so someone
     * standing anywhere on the boundary block is inside by every reading that matters.
     */
    public boolean contains(Location location) {
        return contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public String describe() {
        return minX + " " + minY + " " + minZ + " -> " + maxX + " " + maxY + " " + maxZ;
    }
}
