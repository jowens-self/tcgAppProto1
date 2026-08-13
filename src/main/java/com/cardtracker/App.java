package com.cardtracker;

import com.cardtracker.db.PriceRepository;
import com.cardtracker.model.Category;
import com.cardtracker.model.Group;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.model.Product;
import com.cardtracker.source.PriceSource;
import com.cardtracker.source.tcgcsv.TcgCsvPriceSource;
import com.cardtracker.source.tcgcsv.TcgCsvUrls;
import com.cardtracker.source.tcgcsv.Throttle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daily price pull.
 *
 * Run this once per day. The mirror refreshes around 20:00 UTC, so scheduling
 * it for the small hours local time means you always get a complete day.
 */
public class App {

    public static void main(String[] args) throws Exception {
        int categoryId = TcgCsvUrls.POKEMON;
        // UTC, not host-local - matches the archive backfill's day boundary
        // and the container's own clock. See CLAUDE.md "local-vs-UTC".
        LocalDate snapshotDate = LocalDate.now(ZoneOffset.UTC);

        String url = Env.get("CARDTRACKER_DB_URL", "jdbc:postgresql://localhost:5432/cardtracker");
        String user = Env.get("CARDTRACKER_DB_USER", "cardtracker");
        String pass = Env.get("CARDTRACKER_DB_PASS", "localdev");

        PriceSource source = new TcgCsvPriceSource(Throttle.politeDefault());

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            PriceRepository repo = new PriceRepository(conn);

            if (repo.hasSuccessfulRun(source.name(), snapshotDate)) {
                System.out.printf("Already have a complete pull for %s (UTC) - skipping fetch.%n", snapshotDate);
                return;
            }

            // Created before any of the risky work below (including the gap
            // catch-up) so a failure anywhere in this run - not just in the
            // main pull loop - still lands a "failed" row in ingest_runs
            // instead of vanishing as an uncaught exception with no trace.
            long runId = repo.startRun(source.name(), snapshotDate);
            int totalRows = 0;

            try {
                LocalDate yesterday = snapshotDate.minusDays(1);
                boolean yesterdayCovered = repo.hasSuccessfulRun(source.name(), yesterday)
                        || repo.hasSuccessfulRun(BackfillArchive.SOURCE, yesterday);
                if (!yesterdayCovered) {
                    System.out.printf("No data for %s yet - catching up via archive backfill first...%n", yesterday);
                    BackfillArchive.run(BackfillArchive.EARLIEST_ARCHIVE, yesterday, repo);
                }

                Category category = source.categories().stream()
                        .filter(c -> c.categoryId() == categoryId)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("category " + categoryId + " not found"));
                repo.upsertCategory(category);

                List<Group> groups = source.groups(categoryId);
                System.out.printf("Found %d groups in category %d%n", groups.size(), categoryId);

                for (Group g : groups) {
                    repo.upsertGroup(g);

                    List<Product> products = source.products(categoryId, g.groupId());
                    Set<Integer> excludedInGroup = new HashSet<>();
                    for (Product p : products) {
                        if (isExcluded(p.name())) {
                            repo.upsertExcludedProduct(p.productId(), p.name(), "code_card");
                            excludedInGroup.add(p.productId());
                            continue;
                        }
                        repo.upsertProduct(p);
                    }

                    List<PriceSnapshot> prices = source.pricesForGroup(categoryId, g.groupId(), snapshotDate)
                            .stream()
                            .filter(ps -> !excludedInGroup.contains(ps.productId()))
                            .toList();
                    int written = repo.insertBatch(prices);
                    totalRows += written;
                    System.out.printf("  %-45s %5d products, %5d prices, %5d new%n",
                            truncate(g.name(), 45), products.size() - excludedInGroup.size(), prices.size(), written);
                }

                repo.finishRun(runId, "ok", source.requestsUsed(), totalRows, null);
                System.out.printf("%nDone. %d rows written in %d requests.%n",
                        totalRows, source.requestsUsed());

            } catch (Exception e) {
                // Record the failure before rethrowing, so a broken run is
                // visible in the database rather than only in the console.
                repo.finishRun(runId, "failed", source.requestsUsed(), totalRows,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    /**
     * Digital code-card redemptions - a separate TCGplayer catalog listing
     * for the online code packed inside some sealed product, not a physical
     * card. Behaves nothing like a real card price-wise (see
     * DEVELOPMENT_HISTORY.md) so it's excluded everywhere, not just filtered
     * at query time.
     */
    private static boolean isExcluded(String name) {
        return name != null && name.regionMatches(true, 0, "Code Card", 0, "Code Card".length());
    }
}
