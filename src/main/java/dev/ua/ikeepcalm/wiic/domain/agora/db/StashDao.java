package dev.ua.ikeepcalm.wiic.domain.agora.db;

import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Row access for {@code stash_items}. DB-thread only.
 */
public class StashDao {

    private StashDao() {
    }

    public static void insert(Connection c, StashItem item) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO stash_items (id, owner_uuid, item_bytes, material, amount, display_name, source, source_ref, created_at)
                VALUES (?,?,?,?,?,?,?,?,?)""")) {
            ps.setString(1, item.id().toString());
            ps.setString(2, item.ownerUuid().toString());
            ps.setBytes(3, item.itemBytes());
            ps.setString(4, item.material().name());
            ps.setInt(5, item.amount());
            ps.setString(6, item.displayName());
            ps.setString(7, item.source());
            ps.setString(8, item.sourceRef());
            ps.setLong(9, item.createdAt());
            ps.executeUpdate();
        }
    }

    public static List<StashItem> unclaimedByOwner(Connection c, UUID owner, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT * FROM stash_items WHERE owner_uuid = ? AND claimed_at IS NULL
                ORDER BY created_at ASC LIMIT ?""")) {
            ps.setString(1, owner.toString());
            ps.setInt(2, limit);
            List<StashItem> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
            return result;
        }
    }

    public static int countUnclaimed(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM stash_items WHERE owner_uuid = ? AND claimed_at IS NULL")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * CAS-claims a single row; false means another flow already claimed it.
     */
    public static boolean markClaimed(Connection c, UUID id, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE stash_items SET claimed_at = ? WHERE id = ? AND claimed_at IS NULL")) {
            ps.setLong(1, now);
            ps.setString(2, id.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Reverts a claim mark whose physical hand-over failed (inventory filled up).
     */
    public static void revertClaim(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE stash_items SET claimed_at = NULL WHERE id = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
    }

    private static StashItem map(ResultSet rs) throws SQLException {
        return new StashItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getBytes("item_bytes"),
                Material.valueOf(rs.getString("material")),
                rs.getInt("amount"),
                rs.getString("display_name"),
                rs.getString("source"),
                rs.getString("source_ref"),
                rs.getLong("created_at"));
    }
}
