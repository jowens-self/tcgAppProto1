package com.cardtracker;

import com.cardtracker.db.PriceRepository;
import com.cardtracker.model.Category;
import com.cardtracker.model.Group;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.model.Product;
import com.cardtracker.source.tcgcsv.TcgCsvPriceSource;
import com.cardtracker.source.tcgcsv.Throttle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * One-off smoke test: pull and save a single known card end to end -
 * category, group, product (with its name), and today's price - to prove
 * the products/name-lookup path works before it's wired into the daily
 * App run. Not part of the daily pipeline.
 */
public final class ProductPullTest {

    private static final int CATEGORY_ID = 3;   // Pokemon
    private static final int GROUP_ID = 23353;  // SV: Paldean Fates
    private static final int PRODUCT_ID = 534919; // Mew ex - 232/091

    public static void main(String[] args) throws Exception {
        String url = env("CARDTRACKER_DB_URL", "jdbc:postgresql://localhost:5432/cardtracker");
        String user = env("CARDTRACKER_DB_USER", "cardtracker");
        String pass = env("CARDTRACKER_DB_PASS", "localdev");

        TcgCsvPriceSource source = new TcgCsvPriceSource(Throttle.politeDefault());
        LocalDate today = LocalDate.now(ZoneOffset.UTC); // matches App's convention

        Category category = source.categories().stream()
                .filter(c -> c.categoryId() == CATEGORY_ID)
                .findFirst().orElseThrow(() -> new IllegalStateException("category not found"));

        Group group = source.groups(CATEGORY_ID).stream()
                .filter(g -> g.groupId() == GROUP_ID)
                .findFirst().orElseThrow(() -> new IllegalStateException("group not found"));

        Product product = source.products(CATEGORY_ID, GROUP_ID).stream()
                .filter(p -> p.productId() == PRODUCT_ID)
                .findFirst().orElseThrow(() -> new IllegalStateException("product not found"));

        PriceSnapshot price = source.pricesForGroup(CATEGORY_ID, GROUP_ID, today).stream()
                .filter(p -> p.productId() == PRODUCT_ID)
                .findFirst().orElseThrow(() -> new IllegalStateException("price not found"));

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            PriceRepository repo = new PriceRepository(conn);
            repo.upsertCategory(category);
            repo.upsertGroup(group);
            repo.upsertProduct(product);
            int written = repo.insertBatch(List.of(price));

            System.out.printf("Saved %s (%s): market $%s, trendable $%s, %d new price row(s)%n",
                    product.name(), price.subTypeName(), price.marketPrice(),
                    price.trendablePrice(), written);
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
