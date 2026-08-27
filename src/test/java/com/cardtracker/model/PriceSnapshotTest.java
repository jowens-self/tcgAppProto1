package com.cardtracker.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The market -> mid -> low fallback, and the one price that must never be part
 * of it.
 *
 * Sellers "price park" by listing at absurd values so nothing sells, so
 * high_price is noise dressed as data. A live tcgcsv response checked on
 * 2026-08-12 carried marketPrice ~70 against highPrice 9999 on the same row,
 * which is what turned this from a style preference into a rule.
 */
@DisplayName("PriceSnapshot.trendablePrice")
class PriceSnapshotTest {

    private static PriceSnapshot with(String market, String mid, String low, String high) {
        return new PriceSnapshot(1, "Holofoil", LocalDate.of(2026, 8, 19),
                market == null ? null : new BigDecimal(market),
                low == null ? null : new BigDecimal(low),
                mid == null ? null : new BigDecimal(mid),
                high == null ? null : new BigDecimal(high),
                null);
    }

    @Test
    void prefersMarketPrice() {
        assertEquals(new BigDecimal("70.00"), with("70.00", "80.00", "60.00", "9999.00").trendablePrice());
    }

    @Test
    void fallsBackToMidThenLow() {
        assertEquals(new BigDecimal("80.00"), with(null, "80.00", "60.00", "9999.00").trendablePrice());
        assertEquals(new BigDecimal("60.00"), with(null, null, "60.00", "9999.00").trendablePrice());
    }

    /**
     * The load-bearing one. A parked high_price is orders of magnitude off, so
     * letting it become the trendable price would put a $9,999 reading into a
     * chart of a $70 card.
     */
    @Test
    void neverFallsBackToHighPrice() {
        assertNull(with(null, null, null, "9999.00").trendablePrice(),
                "no usable price must read as null, never as the parked high");
    }

    @Test
    void isNullWhenNothingWasRecorded() {
        assertNull(with(null, null, null, null).trendablePrice());
    }
}
