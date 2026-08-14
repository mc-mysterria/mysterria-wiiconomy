package dev.ua.ikeepcalm.wiic.domain.agora.db;

import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.CourierContract;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Row access for {@code courier_contracts}. DB-thread only.
 */
public class CourierDao {

    private CourierDao() {
    }

    /**
     * Stores a horn contract. Fails (false) when the player already has one, so a
     * double-click can never escrow two horns and lose one.
     */
    public static boolean insert(Connection c, CourierContract contract) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO courier_contracts (player_uuid, horn_item_bytes, courier_type, deposited_at)
                VALUES (?,?,?,?)
                ON CONFLICT(player_uuid) DO NOTHING""")) {
            ps.setString(1, contract.playerUuid().toString());
            ps.setBytes(2, contract.hornItemBytes());
            ps.setString(3, contract.courierType());
            ps.setLong(4, contract.depositedAt());
            return ps.executeUpdate() == 1;
        }
    }

    public static @Nullable CourierContract find(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM courier_contracts WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /**
     * Every contracted player, for the boot-time cache behind {@code hasContract}.
     */
    public static List<UUID> allOwners(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid FROM courier_contracts")) {
            List<UUID> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(UUID.fromString(rs.getString("player_uuid")));
            }
            return result;
        }
    }

    /**
     * Deletes the contract; false means it was already gone (another flow withdrew it).
     */
    public static boolean delete(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM courier_contracts WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            return ps.executeUpdate() == 1;
        }
    }

    private static CourierContract map(ResultSet rs) throws SQLException {
        return new CourierContract(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getBytes("horn_item_bytes"),
                rs.getString("courier_type"),
                rs.getLong("deposited_at"));
    }
}
