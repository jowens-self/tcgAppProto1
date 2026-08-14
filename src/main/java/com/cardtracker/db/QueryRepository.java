package com.cardtracker.db;

import com.cardtracker.model.ItemSearchResult;
import com.cardtracker.model.ItemVariant;
import com.cardtracker.model.PricePoint;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads price history. The counterpart to PriceRepository, which is
 * write-only by design ("Writes price history") - keeping the read path
 * separate rather than growing that class means the ingestion code and the
 * API surface can't accidentally couple.
 *
 * Every method here is a thin call into a function in sql/functions.sql, not
 * inline SQL. That's the Part 14 pattern on purpose: the query logic stays
 * verifiable directly in psql (which is how this project checks its own
 * numbers), and the same definition serves both the API and hand-written
 * analysis, so the two can never quietly diverge.
 */
public class QueryRepository {

    private final Connection conn;

    public QueryRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Name search across products. categoryId null = all tracked games.
     * The limit cap lives in item_search() itself (LEAST(limit, 200)) rather
     * than here, so it holds for psql callers too, not just this class.
     */
    public List<ItemSearchResult> search(String query, Integer categoryId,
                                         boolean includeSealed, int limit, int offset)
            throws Exception {
        String sql = "SELECT product_id, name, set_name, game, is_sealed, "
                + "variant_count, latest_date, url, image_url "
                + "FROM item_search(?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            if (categoryId == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(2, categoryId);
            }
            ps.setBoolean(3, includeSealed);
            ps.setInt(4, limit);
            ps.setInt(5, offset);

            List<ItemSearchResult> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ItemSearchResult(
                            rs.getInt("product_id"),
                            rs.getString("name"),
                            rs.getString("set_name"),
                            rs.getString("game"),
                            rs.getBoolean("is_sealed"),
                            rs.getInt("variant_count"),
                            localDate(rs.getDate("latest_date")),
                            rs.getString("url"),
                            rs.getString("image_url")));
                }
            }
            return out;
        }
    }

    /** Every variant of one product, with current price and all-time high. */
    public List<ItemVariant> variants(int productId) throws Exception {
        String sql = "SELECT sub_type_name, first_date, latest_date, latest_market, "
                + "all_time_high FROM item_variants(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            List<ItemVariant> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ItemVariant(
                            rs.getString("sub_type_name"),
                            localDate(rs.getDate("first_date")),
                            localDate(rs.getDate("latest_date")),
                            rs.getBigDecimal("latest_market"),
                            rs.getBigDecimal("all_time_high")));
                }
            }
            return out;
        }
    }

    /** Price time series for one product+variant. Null dates mean unbounded. */
    public List<PricePoint> history(int productId, String subTypeName,
                                    LocalDate from, LocalDate to) throws Exception {
        String sql = "SELECT snapshot_date, market_price, low_price, mid_price, "
                + "direct_low_price, trendable_price FROM item_history(?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            ps.setString(2, subTypeName);
            setDate(ps, 3, from);
            setDate(ps, 4, to);

            List<PricePoint> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PricePoint(
                            localDate(rs.getDate("snapshot_date")),
                            rs.getBigDecimal("market_price"),
                            rs.getBigDecimal("low_price"),
                            rs.getBigDecimal("mid_price"),
                            rs.getBigDecimal("direct_low_price"),
                            rs.getBigDecimal("trendable_price")));
                }
            }
            return out;
        }
    }

    /**
     * Product identity for one id, or null if unknown. Search already returns
     * this, but a client that arrives by product_id alone (a bookmarked link,
     * a future inventory join) has no other way to resolve a name/set.
     */
    public ItemSearchResult product(int productId) throws Exception {
        String sql = """
                SELECT p.product_id, p.name, g.name AS set_name, c.name AS game,
                       p.is_sealed, p.url, p.image_url,
                       (SELECT COUNT(DISTINCT ps.sub_type_name)::INT
                          FROM price_snapshots ps WHERE ps.product_id = p.product_id) AS variant_count,
                       (SELECT MAX(ps.snapshot_date)
                          FROM price_snapshots ps WHERE ps.product_id = p.product_id) AS latest_date
                  FROM products p
                  JOIN groups g ON g.group_id = p.group_id
                  JOIN categories c ON c.category_id = p.category_id
                 WHERE p.product_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ItemSearchResult(
                        rs.getInt("product_id"),
                        rs.getString("name"),
                        rs.getString("set_name"),
                        rs.getString("game"),
                        rs.getBoolean("is_sealed"),
                        rs.getInt("variant_count"),
                        localDate(rs.getDate("latest_date")),
                        rs.getString("url"),
                        rs.getString("image_url"));
            }
        }
    }

    /**
     * The games actually being tracked, for a client-side filter. Reads
     * tracked_categories joined to categories for the display name, so a game
     * that's listed but has never been pulled still appears (name falls back
     * to the tracked_categories row, which is populated at INSERT time).
     */
    public List<Game> games() throws Exception {
        String sql = """
                SELECT tc.category_id, COALESCE(c.name, tc.name) AS name,
                       (SELECT COUNT(*) FROM products p
                         WHERE p.category_id = tc.category_id)::INT AS product_count
                  FROM tracked_categories tc
                  LEFT JOIN categories c ON c.category_id = tc.category_id
                 ORDER BY name
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Game> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Game(rs.getInt("category_id"), rs.getString("name"),
                        rs.getInt("product_count")));
            }
            return out;
        }
    }

    /** A tracked game, for filter UI. */
    public record Game(int categoryId, String name, int productCount) {
    }

    private static void setDate(PreparedStatement ps, int idx, LocalDate d) throws Exception {
        if (d == null) {
            ps.setNull(idx, Types.DATE);
        } else {
            ps.setObject(idx, d);
        }
    }

    private static LocalDate localDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
