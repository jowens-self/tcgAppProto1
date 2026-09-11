package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a collection was worth on one day.
 *
 * `positions` and `unpriced` travel with the value so a caller can say what
 * the figure leaves out. A day where half the collection had no price is a
 * lower number for a reason, and reporting it as simply lower would be the
 * chart lying quietly.
 */
public record CollectionPoint(
        LocalDate date,
        BigDecimal value,
        int positions,
        int unpriced) {
}
