package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of game_set_overview() - a set's current value and how it moved
 * over a window.
 *
 * Per SET, and singles only: sealed product never contributes to a set
 * aggregate (see sql/functions.sql header and CLAUDE.md). Sealed items stay
 * individually trackable through item_return()/item_all_time_high().
 *
 * Two count/total pairs, and they are not redundant. {@code nPriced} and
 * {@code totalValue} cover every single priced on {@code endDate};
 * {@code nCommon}, {@code totalStart} and {@code totalEnd} cover only the
 * rows priced on BOTH dates, which is the basket {@code pctReturn} is
 * computed over. They diverge whenever a card was priced today but not at
 * the start of the window, and a client should say so rather than show two
 * numbers that look like they ought to match.
 *
 * The top* fields are a deliberate honesty measure, not decoration.
 * Measured 2026-08-18: among Pokemon sets with a >=2% 30-day move, the
 * single biggest contributing row is routinely 50-87% of all movement in
 * the set. A percentage on its own invites a reader to treat a set-wide
 * verdict as broad when one thin-market card produced it. {@code topShare}
 * is a share of GROSS movement (sum of absolute deltas), so it stays
 * meaningful for sets whose gains and losses nearly cancel.
 *
 * {@code pctReturn} is null, never zero, when the starting basket is <= 0.
 */
public record SetOverview(
        int groupId,
        String setName,
        String game,
        LocalDate publishedOn,
        int nPriced,
        BigDecimal totalValue,
        int nCommon,
        BigDecimal totalStart,
        BigDecimal totalEnd,
        BigDecimal pctReturn,
        Integer topProductId,
        String topName,
        String topSubType,
        BigDecimal topDelta,
        BigDecimal topShare,
        String topUrl,
        LocalDate startDate,
        LocalDate endDate) {
}
