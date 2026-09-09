package com.cardtracker.db;

import com.cardtracker.model.Holding;
import com.cardtracker.model.HoldingEvent;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Read and write a user's holdings.
 *
 * The only writing repository the API has. Ingestion still lives in
 * App/BackfillArchive; this writes nothing but inventory_snapshots, and
 * inventory_snapshots is append-only for the same reason price_snapshots is:
 * changing a quantity records a new row, so what you held last month remains
 * answerable. There is deliberately no update() or delete() here - correcting
 * is record(), and selling is record() with quantity 0.
 *
 * Connection is borrowed, not owned, matching the rest of the db package.
 */
public final class HoldingsRepository {

    private final Connection conn;

    public HoldingsRepository(Connection conn) {
        this.conn = conn;
    }

    private static final String RECORD = """
            INSERT INTO inventory_snapshots
                (user_id, snapshot_date, product_id, sub_type_name,
                 condition, quantity, cost_basis)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Append one holding event. snapshotDate is the day the holding is true
     * from, defaulting to today; passing an older date is how a correction
     * says "this was already the case then" rather than "it changed today".
     *
     * Two events for the same card on the same day are both kept, and
     * current_holdings() breaks the tie on ingested_at, so the later one wins
     * without the earlier one being destroyed.
     */
    public void record(long userId, int productId, String subTypeName, String condition,
                       int quantity, BigDecimal costBasis, LocalDate snapshotDate) throws Exception {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0; 0 means sold");
        }
        try (PreparedStatement ps = conn.prepareStatement(RECORD)) {
            ps.setLong(1, userId);
            // UTC, never the host's clock: snapshot_date must mean the same
            // thing here as it does in price_snapshots, or a holding recorded
            // late in a US evening would be dated a day ahead of the prices it
            // gets compared against.
            ps.setObject(2, snapshotDate == null ? LocalDate.now(ZoneOffset.UTC) : snapshotDate);
            ps.setInt(3, productId);
            ps.setString(4, subTypeName);
            if (condition == null || condition.isBlank()) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, condition);
            }
            ps.setInt(6, quantity);
            if (costBasis == null) {
                ps.setNull(7, Types.NUMERIC);
            } else {
                ps.setBigDecimal(7, costBasis);
            }
            ps.executeUpdate();
        }
    }

    /**
     * What the user holds now, priced. asOf moves both the holdings and the
     * prices back in time; null means today.
     */
    public List<Holding> current(long userId, LocalDate asOf) throws Exception {
        String sql = "SELECT * FROM current_holdings(?, ?)";
        List<Holding> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setObject(2, asOf);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Holding(
                            rs.getInt("product_id"),
                            rs.getString("name"),
                            rs.getString("card_number"),
                            rs.getString("set_name"),
                            rs.getString("game"),
                            rs.getString("sub_type_name"),
                            rs.getString("condition"),
                            rs.getInt("quantity"),
                            rs.getBigDecimal("cost_basis"),
                            rs.getBigDecimal("market_price"),
                            rs.getBigDecimal("position_value"),
                            rs.getBigDecimal("gain"),
                            rs.getObject("priced_on", LocalDate.class),
                            rs.getObject("recorded_on", LocalDate.class)));
                }
            }
        }
        return out;
    }

    /**
     * Every recorded event for one card, newest first.
     *
     * Append-only is only worth its cost if the history it preserves can be
     * read, so this is the thing that makes that true rather than theoretical.
     */
    public List<HoldingEvent> historyFor(long userId, int productId) throws Exception {
        String sql = """
                SELECT h.snapshot_date, h.sub_type_name, h.condition,
                       h.quantity, h.cost_basis, h.ingested_at
                  FROM inventory_snapshots h
                 WHERE h.user_id = ? AND h.product_id = ?
                 ORDER BY h.snapshot_date DESC, h.ingested_at DESC
                """;
        List<HoldingEvent> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HoldingEvent(
                            rs.getObject("snapshot_date", LocalDate.class),
                            rs.getString("sub_type_name"),
                            rs.getString("condition"),
                            rs.getInt("quantity"),
                            rs.getBigDecimal("cost_basis"),
                            rs.getObject("ingested_at", java.time.OffsetDateTime.class)));
                }
            }
        }
        return out;
    }
}
