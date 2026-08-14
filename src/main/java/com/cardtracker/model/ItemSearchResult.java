package com.cardtracker.model;

import java.time.LocalDate;

/**
 * One hit from item_search() - a product, not a (product, variant) pair.
 *
 * variantCount rather than a price is deliberate. A product commonly exists
 * as Normal / Holofoil / Reverse Holofoil trading at genuinely different
 * prices, and collapsing those into one "price" is the exact thing
 * CLAUDE.md's sub-type invariant forbids. Search says how many variants
 * exist; ItemVariant (from item_variants()) carries their real prices.
 *
 * setName/game ride along on every hit because card names are ambiguous on
 * their own - 266 Pokemon products match "charizard", dozens named exactly
 * that. A result list without the set is not identifiable.
 */
public record ItemSearchResult(
        int productId,
        String name,
        String setName,
        String game,
        boolean isSealed,
        int variantCount,
        LocalDate latestDate,
        String url,
        String imageUrl) {
}
