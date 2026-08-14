package dev.ua.ikeepcalm.wiic.domain.agora.db;

import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.LedgerEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Row access for {@code ledger_entries}. DB-thread only.
 *
 * <p>Claim protocol: {@code UNCLAIMED → CLAIMING} (all rows for the owner, one
 * transaction, sum returned) → Vault deposit → {@code CLAIMING → CLAIMED}, or
 * {@code CLAIMING → UNCLAIMED} on deposit failure. The intermediate state exists
 * so crash recovery can tell "deposit maybe happened" apart from "never started".
 */
public class LedgerDao {

    private LedgerDao() {
    }

    public static void insert(Connection c, LedgerEntry entry) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ledger_entries (id, owner_uuid, gross, tax, net, source_listing, state, created_at)
                VALUES (?,?,?,?,?,?,'UNCLAIMED',?)""")) {
            ps.setString(1, entry.id().toString());
            ps.setString(2, entry.ownerUuid().toString());
            ps.setLong(3, entry.gross());
            ps.setLong(4, entry.tax());
            ps.setLong(5, entry.net());
            ps.setString(6, entry.sourceListing() == null ? null : entry.sourceListing().toString());
            ps.setLong(7, entry.createdAt());
            ps.executeUpdate();
        }
    }

    public static long sumUnclaimed(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(net), 0) FROM ledger_entries WHERE owner_uuid = ? AND state = 'UNCLAIMED'")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public static List<LedgerEntry> unclaimedEntries(Connection c, UUID owner, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT * FROM ledger_entries WHERE owner_uuid = ? AND state = 'UNCLAIMED'
                ORDER BY created_at DESC LIMIT ?""")) {
            ps.setString(1, owner.toString());
            ps.setInt(2, limit);
            List<LedgerEntry> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
            return result;
        }
    }

    /**
     * Moves every UNCLAIMED row for {@code owner} to CLAIMING. Returns the summed net, 0 if nothing.
     */
    public static long beginClaim(Connection c, UUID owner) throws SQLException {
        long sum = sumUnclaimed(c, owner);
        if (sum <= 0) return 0;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ledger_entries SET state = 'CLAIMING' WHERE owner_uuid = ? AND state = 'UNCLAIMED'")) {
            ps.setString(1, owner.toString());
            ps.executeUpdate();
        }
        return sum;
    }

    public static void finishClaim(Connection c, UUID owner, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ledger_entries SET state = 'CLAIMED', claimed_at = ? WHERE owner_uuid = ? AND state = 'CLAIMING'")) {
            ps.setLong(1, now);
            ps.setString(2, owner.toString());
            ps.executeUpdate();
        }
    }

    public static void revertClaim(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ledger_entries SET state = 'UNCLAIMED' WHERE owner_uuid = ? AND state = 'CLAIMING'")) {
            ps.setString(1, owner.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Whether the owner has any rows stuck in CLAIMING (used only by crash recovery).
     */
    public static boolean hasClaiming(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM ledger_entries WHERE owner_uuid = ? AND state = 'CLAIMING' LIMIT 1")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static LedgerEntry map(ResultSet rs) throws SQLException {
        String source = rs.getString("source_listing");
        return new LedgerEntry(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getLong("gross"),
                rs.getLong("tax"),
                rs.getLong("net"),
                source == null ? null : UUID.fromString(source),
                rs.getLong("created_at"));
    }
}
