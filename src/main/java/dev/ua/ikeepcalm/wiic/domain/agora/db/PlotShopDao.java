package dev.ua.ikeepcalm.wiic.domain.agora.db;

import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotShop;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Row access for {@code plot_shops} — the sign-and-chest counters in rented plots. DB-thread only.
 */
public class PlotShopDao {

    private PlotShopDao() {
    }

    public static List<PlotShop> all(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM plot_shops")) {
            List<PlotShop> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
            return result;
        }
    }

    public static void insert(Connection c, PlotShop shop) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO plot_shops (id, plot_id, world, sign_x, sign_y, sign_z,
                                        chest_x, chest_y, chest_z, owner_uuid, owner_name,
                                        item_bytes, material, display_name, price, bundle,
                                        sold_count, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            ps.setString(1, shop.id().toString());
            ps.setString(2, shop.plotId());
            ps.setString(3, shop.world());
            ps.setInt(4, shop.signX());
            ps.setInt(5, shop.signY());
            ps.setInt(6, shop.signZ());
            ps.setInt(7, shop.chestX());
            ps.setInt(8, shop.chestY());
            ps.setInt(9, shop.chestZ());
            ps.setString(10, shop.ownerUuid().toString());
            ps.setString(11, shop.ownerName());
            ps.setBytes(12, shop.itemBytes());
            ps.setString(13, shop.material() == null ? null : shop.material().name());
            ps.setString(14, shop.displayName());
            ps.setLong(15, shop.price());
            ps.setInt(16, shop.bundle());
            ps.setLong(17, shop.soldCount());
            ps.setLong(18, shop.createdAt());
            ps.executeUpdate();
        }
    }

    /**
     * Rebinds the goods (and clears them when {@code shop} is unstocked).
     */
    public static void updateGoods(Connection c, PlotShop shop) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE plot_shops SET item_bytes = ?, material = ?, display_name = ?,
                                      price = ?, bundle = ?
                WHERE id = ?""")) {
            ps.setBytes(1, shop.itemBytes());
            ps.setString(2, shop.material() == null ? null : shop.material().name());
            ps.setString(3, shop.displayName());
            ps.setLong(4, shop.price());
            ps.setInt(5, shop.bundle());
            ps.setString(6, shop.id().toString());
            ps.executeUpdate();
        }
    }

    /**
     * Bumps the sale counter. Advisory only — the ledger is the record that matters.
     */
    public static void recordSale(Connection c, UUID shopId, int sold) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE plot_shops SET sold_count = sold_count + ? WHERE id = ?")) {
            ps.setInt(1, sold);
            ps.setString(2, shopId.toString());
            ps.executeUpdate();
        }
    }

    public static void delete(Connection c, UUID shopId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM plot_shops WHERE id = ?")) {
            ps.setString(1, shopId.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Drops every counter in a plot — an eviction takes the whole stall with it.
     */
    public static int deleteByPlot(Connection c, String plotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM plot_shops WHERE plot_id = ?")) {
            ps.setString(1, plotId);
            return ps.executeUpdate();
        }
    }

    private static PlotShop map(ResultSet rs) throws SQLException {
        String materialName = rs.getString("material");
        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        return new PlotShop(
                UUID.fromString(rs.getString("id")),
                rs.getString("plot_id"),
                rs.getString("world"),
                rs.getInt("sign_x"), rs.getInt("sign_y"), rs.getInt("sign_z"),
                rs.getInt("chest_x"), rs.getInt("chest_y"), rs.getInt("chest_z"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"),
                rs.getBytes("item_bytes"),
                material,
                rs.getString("display_name"),
                rs.getLong("price"),
                Math.max(1, rs.getInt("bundle")),
                rs.getLong("sold_count"),
                rs.getLong("created_at"));
    }

    /**
     * The counter registered at a sign block, or null.
     */
    public static @Nullable PlotShop bySign(Connection c, String world, int x, int y, int z) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT * FROM plot_shops WHERE world = ? AND sign_x = ? AND sign_y = ? AND sign_z = ?""")) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }
}
