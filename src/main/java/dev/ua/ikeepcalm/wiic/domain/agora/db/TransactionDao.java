package dev.ua.ikeepcalm.wiic.domain.agora.db;

import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Append-only audit trail ({@code transactions} table). DB-thread only.
 */
public class TransactionDao {

    private TransactionDao() {
    }

    public static void log(Connection c, String type, UUID actor, @Nullable UUID counterparty,
                           @Nullable UUID listingId, long amount, @Nullable String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO transactions (type, actor_uuid, counterparty, listing_id, amount, detail, created_at)
                VALUES (?,?,?,?,?,?,?)""")) {
            ps.setString(1, type);
            ps.setString(2, actor.toString());
            ps.setString(3, counterparty == null ? null : counterparty.toString());
            ps.setString(4, listingId == null ? null : listingId.toString());
            ps.setLong(5, amount);
            ps.setString(6, detail);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Deletes audit rows older than {@code cutoff} (startup housekeeping).
     */
    public static int purgeOlderThan(Connection c, long cutoff) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM transactions WHERE created_at < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }
}
