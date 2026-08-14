package dev.ua.ikeepcalm.wiic.domain.agora.plots.model;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A prestige plot's shape, as defined by an admin in {@code market.yml}
 * ({@code plots.regions.<id>}) — never a database row. Rental state for the same id
 * lives in {@link PlotRental}; the two are joined by {@link #id()}.
 *
 * <p>Corners are inclusive block coordinates in the market world, normalised so
 * {@code min <= max} on every axis. The optional vendor spot is where the plot's
 * {@code PLOT_VENDOR} NPC is spawned on rent.
 */
public record PlotRegion(
        String id,
        String displayName,
        Material icon,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        @Nullable Spot vendorSpot
) {

    /**
     * Where the plot's vendor NPC stands.
     */
    public record Spot(double x, double y, double z, float yaw) {
        public Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, 0f);
        }
    }

    /**
     * Parses one {@code plots.regions.<id>} section, or null when the corners are
     * missing/malformed (an admin defined the id but never ran {@code plot define}).
     */
    public static @Nullable PlotRegion fromConfig(String id, ConfigurationSection section) {
        List<Integer> min = section.getIntegerList("min");
        List<Integer> max = section.getIntegerList("max");
        if (min.size() < 3 || max.size() < 3) return null;

        Material configured = Material.matchMaterial(section.getString("icon", "OAK_SIGN"));
        // A block-only material (WATER, and friends) would blow up as a GUI ItemStack.
        Material icon = configured != null && configured.isItem() ? configured : Material.OAK_SIGN;
        List<Double> spot = section.getDoubleList("vendor-spot");
        Spot vendorSpot = spot.size() >= 3
                ? new Spot(spot.get(0), spot.get(1), spot.get(2),
                spot.size() >= 4 ? spot.get(3).floatValue() : 0f)
                : null;

        return new PlotRegion(id,
                section.getString("display-name", id),
                icon,
                Math.min(min.get(0), max.get(0)), Math.min(min.get(1), max.get(1)), Math.min(min.get(2), max.get(2)),
                Math.max(min.get(0), max.get(0)), Math.max(min.get(1), max.get(1)), Math.max(min.get(2), max.get(2)),
                vendorSpot);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Location location) {
        return contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Block count of the cuboid — the unit the snapshot/restore pacing budgets against.
     */
    public int volume() {
        return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public @Nullable Location vendorLocation(@Nullable World world) {
        return world != null && vendorSpot != null ? vendorSpot.toLocation(world) : null;
    }
}
