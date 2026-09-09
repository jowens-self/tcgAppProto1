package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of current_holdings(): a card you own, priced.
 *
 * gain is null rather than zero when costBasis is unknown, because "you did
 * not tell me what you paid" and "you broke even" are different answers.
 *
 * name and setName are null for a product later removed from the catalog
 * (code cards are, routinely). The holding survives unnamed rather than
 * disappearing from someone's collection.
 */
public record Holding(
        int productId,
        String name,
        String cardNumber,
        String setName,
        String game,
        String subTypeName,
        String condition,
        int quantity,
        BigDecimal costBasis,
        BigDecimal marketPrice,
        BigDecimal positionValue,
        BigDecimal gain,
        LocalDate pricedOn,
        LocalDate recordedOn) {
}
