package com.cardtracker.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Change {

    private Change() {
    }

    /**
     * Percent change from {@code from} to {@code to}, e.g. 12.50 for +12.5%.
     * Null on a zero or negative (or missing) starting price - there is no
     * meaningful percent change against a non-positive baseline, so this
     * returns null rather than 0 or infinity. Callers must handle null.
     */
    public static BigDecimal percent(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() <= 0) {
            return null;
        }
        return to.subtract(from)
                .divide(from, 8, RoundingMode.HALF_UP)
                .movePointRight(2)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
