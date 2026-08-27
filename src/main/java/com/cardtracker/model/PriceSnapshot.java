package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceSnapshot(
        int productId,
        String subTypeName,
        LocalDate snapshotDate,
        BigDecimal marketPrice,
        BigDecimal lowPrice,
        BigDecimal midPrice,
        BigDecimal highPrice,
        BigDecimal directLowPrice) {

    /**
     * market_price if present, else mid, else low. Never high, because sellers
     * price-park it, so it's noise, not signal (see CLAUDE.md).
     */
    public BigDecimal trendablePrice() {
        if (marketPrice != null) return marketPrice;
        if (midPrice != null) return midPrice;
        return lowPrice;
    }
}
