package dev.ua.ikeepcalm.wiic.domain.agora.db;

import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRegion;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRental;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Row access for {@code plots} and {@code plot_snapshots}. DB-thread only.
 */
public class PlotDao {

    private PlotDao() {
    }

    // -------------------------------------------------------------------------
    // Rentals
    // -------------------------------------------------------------------------

    public static List<PlotRental> all(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plots WHERE renter_uuid IS NOT NULL")) {
            List<PlotRental> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
            return result;
        }
    }

    public static @Nullable PlotRental find(Connection c, String plotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plots WHERE plot_id = ? AND renter_uuid IS NOT NULL")) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public static int countByRenter(Connection c, UUID renter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM plots WHERE renter_uuid = ?")) {
            ps.setString(1, renter.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Claims a free plot for {@code renter}. The upsert's {@code WHERE} clause makes
     * this the single atomic gate against two players renting the same plot — false
     * means somebody already holds it.
     */
    public static boolean claim(Connection c, String plotId, UUID renter, String renterName,
                                long now, long paidUntil) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO plots (plot_id, renter_uuid, renter_name, rented_at, paid_until, vendor_npc_id)
                VALUES (?,?,?,?,?,NULL)
                ON CONFLICT(plot_id) DO UPDATE SET
                  renter_uuid = excluded.renter_uuid,
                  renter_name = excluded.renter_name,
                  rented_at   = excluded.rented_at,
                  paid_until  = excluded.paid_until,
                  vendor_npc_id = NULL
                WHERE plots.renter_uuid IS NULL""")) {
            ps.setString(1, plotId);
            ps.setString(2, renter.toString());
            ps.setString(3, renterName);
            ps.setLong(4, now);
            ps.setLong(5, paidUntil);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Extends a rental the caller still holds by {@code periodMs}. False means they lost it.
     */
    public static boolean extend(Connection c, String plotId, UUID renter, long now, long periodMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE plots SET paid_until = MAX(paid_until, ?) + ?
                WHERE plot_id = ? AND renter_uuid = ?""")) {
            ps.setLong(1, now);
            ps.setLong(2, periodMs);
            ps.setString(3, plotId);
            ps.setString(4, renter.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Drops the rental row (voluntary release or eviction).
     */
    public static void clearRental(Connection c, String plotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM plots WHERE plot_id = ?")) {
            ps.setString(1, plotId);
            ps.executeUpdate();
        }
    }

    public static void setVendorNpc(Connection c, String plotId, @Nullable Integer npcId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE plots SET vendor_npc_id = ? WHERE plot_id = ?")) {
            if (npcId == null) ps.setNull(1, java.sql.Types.INTEGER);
            else ps.setInt(1, npcId);
            ps.setString(2, plotId);
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // Snapshots
    // -------------------------------------------------------------------------

    public static void upsertSnapshot(Connection c, PlotRegion region, String world,
                                      byte[] blocks, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO plot_snapshots (plot_id, world, min_x, min_y, min_z, max_x, max_y, max_z, blocks, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(plot_id) DO UPDATE SET
                  world = excluded.world,
                  min_x = excluded.min_x, min_y = excluded.min_y, min_z = excluded.min_z,
                  max_x = excluded.max_x, max_y = excluded.max_y, max_z = excluded.max_z,
                  blocks = excluded.blocks, created_at = excluded.created_at""")) {
            ps.setString(1, region.id());
            ps.setString(2, world);
            ps.setInt(3, region.minX());
            ps.setInt(4, region.minY());
            ps.setInt(5, region.minZ());
            ps.setInt(6, region.maxX());
            ps.setInt(7, region.maxY());
            ps.setInt(8, region.maxZ());
            ps.setBytes(9, blocks);
            ps.setLong(10, now);
            ps.executeUpdate();
        }
    }

    /**
     * The stored baseline blob, or null when the plot was never snapshotted.
     */
    public static byte @Nullable [] snapshot(Connection c, String plotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT blocks FROM plot_snapshots WHERE plot_id = ?")) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes("blocks") : null;
            }
        }
    }

    public static boolean hasSnapshot(Connection c, String plotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM plot_snapshots WHERE plot_id = ?")) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static PlotRental map(ResultSet rs) throws SQLException {
        int npcId = rs.getInt("vendor_npc_id");
        // wasNull() reflects the most recent getter, so resolve it before reading anything else.
        Integer vendorNpcId = rs.wasNull() ? null : npcId;
        return new PlotRental(
                rs.getString("plot_id"),
                UUID.fromString(rs.getString("renter_uuid")),
                rs.getString("renter_name"),
                rs.getLong("rented_at"),
                rs.getLong("paid_until"),
                vendorNpcId);
    }
}
