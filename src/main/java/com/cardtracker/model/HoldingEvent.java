package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One recorded change to a holding: the audit trail behind a quantity.
 *
 * Its own record rather than a Holding with six nulls. A Holding is a priced
 * position; an event is what you said and when, with no price attached,
 * because the price of a past event is a question about the market on that
 * day rather than a property of the entry.
 *
 * quantity 0 is a sale. recordedOn is the day the change is true from;
 * enteredAt is when it was typed, which is what orders two changes made on
 * the same day.
 */
public record HoldingEvent(
        LocalDate recordedOn,
        String subTypeName,
        String condition,
        int quantity,
        BigDecimal costBasis,
        OffsetDateTime enteredAt) {
}
