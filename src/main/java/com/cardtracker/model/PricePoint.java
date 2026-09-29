package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of a variant's price history, from item_history().
 *
 * No highPrice field, on purpose. Sellers "price park" by listing at absurd
 * values so nothing sells, so high_price is noise the project stores but
 * never computes on. Leaving it off this record (and out of item_history())
 * makes that rule structural instead of something a future chart author has
 * to remember, the same way set_return() hardcodes sealed product out rather
 * than offering a parameter.
 *
 * trendablePrice is the documented market->mid->low fallback precomputed, so
 * a caller charting this data cannot get the fallback rule wrong.
 *
 * phase is "pre_release", "early" or null (settled), from price_phase(), so a
 * chart can shade or hide launch pricing without knowing the rule.
 */
public record PricePoint(
        LocalDate snapshotDate,
        BigDecimal marketPrice,
        BigDecimal lowPrice,
        BigDecimal midPrice,
        BigDecimal directLowPrice,
        BigDecimal trendablePrice,
        String phase) {
}
