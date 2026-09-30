package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One variant (sub-type) of a product, from item_variants().
 *
 * The bridge between search and item_return()/item_all_time_high(): both of
 * those need a subTypeName, and a caller cannot guess which variants a given
 * product actually has. It varies per product and changes over time, which
 * is why firstDate is reported rather than assumed (Altaria 83514's Normal
 * variant starts 2025-03-28 while its Holofoil goes back to 2024-02-08).
 *
 * latestMarket is raw market_price with no fallback, so "no market price
 * that day" reads as null instead of silently becoming a mid or low price.
 * allTimeHigh does use the documented market->mid->low fallback, matching
 * item_all_time_high() exactly.
 *
 * flipLow, flipHigh and flipReversals are set when this variant's price keeps
 * flipping between two levels (price_flips): the two levels, and how many
 * times it went back in the last 180 days. Null otherwise.
 */
public record ItemVariant(
        String subTypeName,
        LocalDate firstDate,
        LocalDate latestDate,
        BigDecimal latestMarket,
        BigDecimal allTimeHigh,
        BigDecimal flipLow,
        BigDecimal flipHigh,
        Integer flipReversals) {
}
