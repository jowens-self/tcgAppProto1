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
 *
 * cardNumber/rarity are needed for the same reason and are not decoration:
 * a single set routinely reprints one card at several rarities, and those
 * copies trade at very different prices. SV03 Obsidian Flames has four
 * "Charizard ex" - 125/197 Double Rare, 215/197 Ultra Rare, 223/197 Special
 * Illustration Rare, 228/197 Hyper Rare. Both come from extendedData rather
 * than the name: TCGplayer only sometimes appends the number to the name
 * (5,276 of 34,435 singles), while extendedData carries Number and Rarity
 * for all of them, in both tracked games. Null for sealed product, which
 * has neither.
 */
public record ItemSearchResult(
        int productId,
        String name,
        String cardNumber,
        String rarity,
        String setName,
        String game,
        boolean isSealed,
        int variantCount,
        LocalDate latestDate,
        String url,
        String imageUrl) {
}
