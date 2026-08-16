package dev.ua.ikeepcalm.wiic.domain.agora.db;

import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.Listing;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ListingState;
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
 * Row access for {@code listings}. All methods are synchronous and must run on the
 * DB thread (inside {@code MarketDatabase.submit}/{@code transactionThenMain}).
 * State transitions are compare-and-swap {@code UPDATE ... WHERE state = ?} so two
 * racing flows can never both win.
 */
public class ListingDao {

    public enum Sort { NEWEST, CHEAPEST }

    private ListingDao() {}

    public static void insert(Connection c, Listing l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO listings (id, seller_uuid, seller_name, item_bytes, material, amount, display_name,
                  category, is_coi_item, coi_pathway, coi_sequence, price, state, plot_id, created_at, expires_at,
                  value_key)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            ps.setString(1, l.id().toString());
            ps.setString(2, l.sellerUuid().toString());
            ps.setString(3, l.sellerName());
            ps.setBytes(4, l.itemBytes());
            ps.setString(5, l.material().name());
            ps.setInt(6, l.amount());
            ps.setString(7, l.displayName());
            ps.setString(8, l.category());
            ps.setInt(9, l.coiItem() ? 1 : 0);
            ps.setString(10, l.coiPathway());
            if (l.coiSequence() != null) ps.setInt(11, l.coiSequence()); else ps.setNull(11, java.sql.Types.INTEGER);
            ps.setLong(12, l.price());
            ps.setString(13, l.state().name());
            ps.setString(14, l.plotId());
            ps.setLong(15, l.createdAt());
            ps.setLong(16, l.expiresAt());
            ps.setString(17, l.valueKey());
            ps.executeUpdate();
        }
    }

    /**
     * Per-unit prices of the most recent completed sales of {@code valueKey}, returned
     * in ascending order so callers can take the median directly.
     *
     * <p>Prices are divided by the stack size because a listing of 16 ingredients and a
     * listing of one are the same goods at different quantities — only the unit price is
     * comparable. The inner query takes the most <i>recent</i> sales; the outer one sorts
     * those by price, so a long-dead price can never anchor today's market.
     */
    public static List<Long> recentSoldUnitPrices(Connection c, String valueKey, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT unit FROM (
                  SELECT price / MAX(amount, 1) AS unit, sold_at FROM listings
                  WHERE value_key = ? AND state = 'SOLD' AND sold_at IS NOT NULL
                  ORDER BY sold_at DESC LIMIT ?
                ) ORDER BY unit ASC""")) {
            ps.setString(1, valueKey);
            ps.setInt(2, limit);
            List<Long> prices = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) prices.add(rs.getLong(1));
            }
            return prices;
        }
    }

    public static @Nullable Listing findById(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM listings WHERE id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /** CAS ACTIVE → PENDING_PAYMENT. Enforces price ceiling and no self-purchase in the same statement. */
    public static boolean reserve(Connection c, UUID id, UUID buyer, long maxPrice, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE listings SET state = 'PENDING_PAYMENT', buyer_uuid = ?, reserved_at = ?
                WHERE id = ? AND state = 'ACTIVE' AND price <= ? AND seller_uuid != ?""")) {
            ps.setString(1, buyer.toString());
            ps.setLong(2, now);
            ps.setString(3, id.toString());
            ps.setLong(4, maxPrice);
            ps.setString(5, buyer.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** CAS PENDING_PAYMENT (held by this buyer) → back to ACTIVE, e.g. after a failed withdraw. */
    public static boolean releaseReservation(Connection c, UUID id, UUID buyer) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE listings SET state = 'ACTIVE', buyer_uuid = NULL, reserved_at = NULL
                WHERE id = ? AND state = 'PENDING_PAYMENT' AND buyer_uuid = ?""")) {
            ps.setString(1, id.toString());
            ps.setString(2, buyer.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** CAS PENDING_PAYMENT (held by this buyer) → SOLD. */
    public static boolean markSold(Connection c, UUID id, UUID buyer, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE listings SET state = 'SOLD', sold_at = ?
                WHERE id = ? AND state = 'PENDING_PAYMENT' AND buyer_uuid = ?""")) {
            ps.setLong(1, now);
            ps.setString(2, id.toString());
            ps.setString(3, buyer.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** CAS ACTIVE → CANCELLED (seller withdrawing their own listing). */
    public static boolean cancel(Connection c, UUID id, UUID seller) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE listings SET state = 'CANCELLED' WHERE id = ? AND state = 'ACTIVE' AND seller_uuid = ?")) {
            ps.setString(1, id.toString());
            ps.setString(2, seller.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** CAS ACTIVE → EXPIRED for a single listing (sweeper moves items to stash one by one). */
    public static boolean expire(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE listings SET state = 'EXPIRED' WHERE id = ? AND state = 'ACTIVE'")) {
            ps.setString(1, id.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public static List<Listing> findExpirable(Connection c, long now, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM listings WHERE state = 'ACTIVE' AND expires_at <= ? LIMIT ?")) {
            ps.setLong(1, now);
            ps.setInt(2, limit);
            return mapAll(ps);
        }
    }

    /** Releases reservations older than {@code cutoff} (buyer thread died mid-purchase). */
    public static int releaseStaleReservations(Connection c, long cutoff) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE listings SET state = 'ACTIVE', buyer_uuid = NULL, reserved_at = NULL
                WHERE state = 'PENDING_PAYMENT' AND reserved_at < ?""")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    public static List<Listing> browse(Connection c, @Nullable String category, @Nullable Boolean coiOnly,
                                       @Nullable String plotId, Sort sort, int limit, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM listings WHERE state = 'ACTIVE'");
        List<Object> params = new ArrayList<>();
        if (category != null) { sql.append(" AND category = ?"); params.add(category); }
        if (coiOnly != null && coiOnly) sql.append(" AND is_coi_item = 1");
        if (plotId != null) { sql.append(" AND plot_id = ?"); params.add(plotId); }
        sql.append(sort == Sort.CHEAPEST ? " ORDER BY price ASC, created_at DESC" : " ORDER BY created_at DESC");
        sql.append(" LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        try (PreparedStatement ps = prepare(c, sql.toString(), params)) {
            return mapAll(ps);
        }
    }

    /**
     * Name/material search. {@code %} and {@code _} are LIKE wildcards, so a player typing
     * "diamond_sword" must have the underscore escaped rather than stripped — the material
     * column is full of them.
     */
    public static List<Listing> search(Connection c, String query, int limit) throws SQLException {
        String escaped = query.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String nameLike = "%" + escaped + "%";
        // Nobody types "diamond_sword". A space becomes a bare single-character wildcard so
        // "diamond sword" still finds DIAMOND_SWORD.
        String materialLike = "%" + escaped.replace(' ', '_') + "%";
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT * FROM listings WHERE state = 'ACTIVE'
                AND (LOWER(display_name) LIKE ? ESCAPE '\\' OR LOWER(material) LIKE ? ESCAPE '\\')
                ORDER BY created_at DESC LIMIT ?""")) {
            ps.setString(1, nameLike);
            ps.setString(2, materialLike);
            ps.setInt(3, limit);
            return mapAll(ps);
        }
    }

    public static List<Listing> searchByPathway(Connection c, String pathway, @Nullable Integer sequence, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM listings WHERE state = 'ACTIVE' AND is_coi_item = 1 AND coi_pathway = ?");
        List<Object> params = new ArrayList<>();
        params.add(pathway);
        if (sequence != null) { sql.append(" AND coi_sequence = ?"); params.add(sequence); }
        sql.append(" ORDER BY coi_sequence ASC, created_at DESC LIMIT ?");
        params.add(limit);
        try (PreparedStatement ps = prepare(c, sql.toString(), params)) {
            return mapAll(ps);
        }
    }

    public static List<String> distinctPathways(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT DISTINCT coi_pathway FROM listings
                WHERE state = 'ACTIVE' AND is_coi_item = 1 AND coi_pathway IS NOT NULL ORDER BY coi_pathway""")) {
            List<String> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
            return result;
        }
    }

    /**
     * Everything the Informant can point at: pathways with live goods, and how many
     * beyonder items carry no pathway at all.
     *
     * @param unaligned live beyonder listings with a null pathway — ingredients, in
     *                  practice, since CoI tags those with only
     *                  {@code circleofimagination:ingredient}. Counting them separately
     *                  is what keeps a shelf full of ingredients from reading as empty.
     */
    public record CoiIndex(List<String> pathways, int unaligned) {}

    public static CoiIndex coiIndex(Connection c) throws SQLException {
        List<String> pathways = distinctPathways(c);
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM listings
                WHERE state = 'ACTIVE' AND is_coi_item = 1 AND coi_pathway IS NULL""");
             ResultSet rs = ps.executeQuery()) {
            return new CoiIndex(pathways, rs.next() ? rs.getInt(1) : 0);
        }
    }

    /** Beyonder goods with no pathway of their own — ingredients. */
    public static List<Listing> searchUnalignedCoi(Connection c, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT * FROM listings
                WHERE state = 'ACTIVE' AND is_coi_item = 1 AND coi_pathway IS NULL
                ORDER BY created_at DESC LIMIT ?""")) {
            ps.setInt(1, limit);
            return mapAll(ps);
        }
    }

    public static List<Listing> bySeller(Connection c, UUID seller) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT * FROM listings WHERE seller_uuid = ? AND state IN ('ACTIVE', 'PENDING_PAYMENT')
                ORDER BY created_at DESC""")) {
            ps.setString(1, seller.toString());
            return mapAll(ps);
        }
    }

    public static int countActiveBySeller(Connection c, UUID seller) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM listings WHERE seller_uuid = ? AND state IN ('ACTIVE', 'PENDING_PAYMENT')")) {
            ps.setString(1, seller.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, List<Object> params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            switch (p) {
                case Integer v -> ps.setInt(i + 1, v);
                case Long v -> ps.setLong(i + 1, v);
                default -> ps.setString(i + 1, String.valueOf(p));
            }
        }
        return ps;
    }

    private static List<Listing> mapAll(PreparedStatement ps) throws SQLException {
        List<Listing> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(map(rs));
        }
        return result;
    }

    private static Listing map(ResultSet rs) throws SQLException {
        int seq = rs.getInt("coi_sequence");
        boolean seqNull = rs.wasNull();
        String buyer = rs.getString("buyer_uuid");
        return new Listing(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("seller_uuid")),
                rs.getString("seller_name"),
                rs.getBytes("item_bytes"),
                Material.valueOf(rs.getString("material")),
                rs.getInt("amount"),
                rs.getString("display_name"),
                rs.getString("category"),
                rs.getInt("is_coi_item") == 1,
                rs.getString("coi_pathway"),
                seqNull ? null : seq,
                rs.getLong("price"),
                ListingState.valueOf(rs.getString("state")),
                buyer == null ? null : UUID.fromString(buyer),
                rs.getString("plot_id"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                rs.getString("value_key"));
    }
}
