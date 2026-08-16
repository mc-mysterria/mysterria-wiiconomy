package dev.ua.ikeepcalm.wiic.domain.agora.db;

import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketEntrance;
import dev.ua.ikeepcalm.wiic.utils.WorldUtil;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Row access for {@code entrances} and {@code return_points}. DB-thread only. */
public class EntranceDao {

    private EntranceDao() {}

    // -------------------------------------------------------------------------
    // Entrances
    // -------------------------------------------------------------------------

    public static void insert(Connection c, MarketEntrance e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO entrances (id, land_id, world, x, y, z, created_by, created_at)
                VALUES (?,?,?,?,?,?,?,?)""")) {
            ps.setString(1, e.id().toString());
            ps.setString(2, e.landId());
            ps.setString(3, e.world());
            ps.setInt(4, e.x());
            ps.setInt(5, e.y());
            ps.setInt(6, e.z());
            ps.setString(7, e.createdBy().toString());
            ps.setLong(8, e.createdAt());
            ps.executeUpdate();
        }
    }

    public static @Nullable MarketEntrance byLocation(Connection c, Location loc) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM entrances WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            ps.setString(1, WorldUtil.id(loc.getWorld()));
            ps.setInt(2, loc.getBlockX());
            ps.setInt(3, loc.getBlockY());
            ps.setInt(4, loc.getBlockZ());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public static @Nullable MarketEntrance byLandId(Connection c, String landId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM entrances WHERE land_id = ?")) {
            ps.setString(1, landId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public static List<MarketEntrance> all(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM entrances")) {
            List<MarketEntrance> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
            return result;
        }
    }

    public static void delete(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM entrances WHERE id = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // Return points
    // -------------------------------------------------------------------------

    public static void upsertReturnPoint(Connection c, UUID player, String serializedLocation,
                                         @Nullable UUID entranceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO return_points (player_uuid, location, entrance_id, created_at) VALUES (?,?,?,?)
                ON CONFLICT (player_uuid) DO UPDATE SET location = excluded.location,
                  entrance_id = excluded.entrance_id, created_at = excluded.created_at""")) {
            ps.setString(1, player.toString());
            ps.setString(2, serializedLocation);
            ps.setString(3, entranceId == null ? null : entranceId.toString());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Where a player came in, and through which door.
     *
     * @param entranceId the entrance they used, or null for a row written before the
     *                   column existed. It is the second chance at getting them home:
     *                   when the exact spot they left has become unusable, the door
     *                   itself is still a far better answer than the world spawn.
     */
    public record ReturnPoint(String location, @Nullable UUID entranceId) {}

    public static @Nullable ReturnPoint returnPoint(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT location, entrance_id FROM return_points WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String entranceId = rs.getString(2);
                return new ReturnPoint(rs.getString(1),
                        entranceId == null ? null : UUID.fromString(entranceId));
            }
        }
    }

    public static void deleteReturnPoint(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM return_points WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
    }

    private static MarketEntrance map(ResultSet rs) throws SQLException {
        return new MarketEntrance(
                UUID.fromString(rs.getString("id")),
                rs.getString("land_id"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                UUID.fromString(rs.getString("created_by")),
                rs.getLong("created_at"));
    }
}
