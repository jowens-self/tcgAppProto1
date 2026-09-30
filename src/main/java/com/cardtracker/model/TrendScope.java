package com.cardtracker.model;

/**
 * Which prices a trend (Movers, a set's change) should count, beyond the
 * settled ones it always counts. All three are left out by default because
 * all three mislead a trend, and values never leave any of them out.
 *
 * - includePreRelease: prices from before a card's release (preorders).
 * - includeEarly: prices from its first weeks while they settle.
 *   Which prices fall in each is decided in SQL by launch_noise(); what they
 *   look like in the data: sql/migrations/005_launch_pricing.sql.
 * - includeFlipping: cards whose price keeps jumping between two far-apart
 *   levels (price_flips, sql/migrations/007_price_flips.sql).
 */
public record TrendScope(boolean includePreRelease, boolean includeEarly,
                         boolean includeFlipping) {

    /** The default: only settled prices that hold still count. */
    public static final TrendScope SETTLED_ONLY = new TrendScope(false, false, false);
}
