package dev.ua.ikeepcalm.wiic.domain.agora.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-player per-day listing counters. Keyed by server-local calendar date, so
 * there is no midnight reset task — a new day simply starts a fresh row.
 * DB-thread only.
 */
public class DailyCounterDao {

    private DailyCounterDao() {
    }

    public static String today() {
        return LocalDate.now().toString();
    }

    public static int listingsCreatedToday(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT listings_created FROM daily_counters WHERE owner_uuid = ? AND day = ?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, today());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Increments today's counter iff it is still below {@code limit}.
     * Returns false when the limit is hit — callers must roll back their transaction.
     */
    public static boolean incrementIfBelow(Connection c, UUID owner, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO daily_counters (owner_uuid, day, listings_created) VALUES (?, ?, 0)
                ON CONFLICT (owner_uuid, day) DO NOTHING""")) {
            ps.setString(1, owner.toString());
            ps.setString(2, today());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE daily_counters SET listings_created = listings_created + 1
                WHERE owner_uuid = ? AND day = ? AND listings_created < ?""")) {
            ps.setString(1, owner.toString());
            ps.setString(2, today());
            ps.setInt(3, limit);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Deletes counter rows older than {@code cutoffDay} (yyyy-MM-dd, startup housekeeping).
     */
    public static int purgeBefore(Connection c, String cutoffDay) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM daily_counters WHERE day < ?")) {
            ps.setString(1, cutoffDay);
            return ps.executeUpdate();
        }
    }
}
