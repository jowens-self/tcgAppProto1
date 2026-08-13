package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Mirrors the `inventory_snapshots` table in sql/schema.sql. */
public record InventorySnapshot(
        LocalDate snapshotDate,
        Integer productId,
        Long skuId,
        String subTypeName,
        String condition,
        int quantity,
        BigDecimal yourPrice,
        String sourceFile) {
}
