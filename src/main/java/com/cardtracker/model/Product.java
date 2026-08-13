package com.cardtracker.model;

/**
 * extendedData is kept as raw JSON, not parsed into fields - its shape
 * (card number, rarity, HP, attacks...) varies by category and isn't worth
 * modeling in Java. It lands straight in the `products.extended_data` jsonb
 * column, queryable there if needed.
 */
public record Product(
        int productId,
        int groupId,
        int categoryId,
        String name,
        String cleanName,
        String imageUrl,
        String url,
        String modifiedOn,
        String extendedDataJson) {
}
