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
 * Daily price pull, for every category in `tracked_categories` (see
 * sql/schema.sql - adding a card game is an INSERT, not a code change).
 *
 * Run this once per day. The mirror refreshes around 20:00 UTC, so scheduling
 * it for the small hours local time means you always get a complete day.
 */
public class App {

    public static void main(String[] args) throws Exception {
        // UTC, not host-local - matches the archive backfill's day boundary
        // and the container's own clock. See CLAUDE.md "local-vs-UTC".
        LocalDate snapshotDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate yesterday = snapshotDate.minusDays(1);

        String url = Env.get("CARDTRACKER_DB_URL", "jdbc:postgresql://localhost:5432/cardtracker");
        String user = Env.get("CARDTRACKER_DB_USER", "cardtracker");
        String pass = Env.get("CARDTRACKER_DB_PASS", "localdev");

        PriceSource source = new TcgCsvPriceSource(Throttle.politeDefault());

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            PriceRepository repo = new PriceRepository(conn);
            List<Integer> categoryIds = repo.trackedCategoryIds();

            boolean anyFailed = false;

            // Gap catch-up for every tracked category, once, before any
            // live pull. BackfillArchive.run() already skips anything
            // already covered via one indexed lookup per (category, day),
            // and shares one archive download across every category that
            // still needs a given day - so calling it unconditionally here
            // is cheap on a normal day, not just "cheap if we bother to
            // check first." Its own per-(category, day) loop already logs
            // failures to ingest_runs; this try/catch only guards the setup
            // phase before that loop starts (temp dir creation, the initial
            // repo queries) - a narrower version of the exact bug fixed in
            // DEVELOPMENT_HISTORY.md Part 13, where a failure before any
            // ingest_runs row existed could otherwise vanish with no trace.
            // A catch-up hiccup here doesn't block today's live pulls below
            // from still being attempted and properly tracked.
            try {
                BackfillArchive.run(BackfillArchive.EARLIEST_ARCHIVE, yesterday, repo);
            } catch (Exception e) {
                anyFailed = true;
                System.out.printf("Gap catch-up FAILED: %s: %s%n",
                        e.getClass().getSimpleName(), e.getMessage());
            }

            for (int categoryId : categoryIds) {
                try {
                    pullCategory(source, repo, categoryId, snapshotDate);
                } catch (Exception e) {
                    anyFailed = true;
                    System.out.printf("[%d] FAILED: %s: %s%n",
                            categoryId, e.getClass().getSimpleName(), e.getMessage());
                }
            }
            if (anyFailed) {
                throw new IllegalStateException(
                        "One or more tracked categories failed - see ingest_runs for details.");
            }
        }
    }

    /**
     * One category's full live pull: catalog (category/groups/products) and
     * today's prices. Failures here are caught by the caller so one
     * category failing doesn't stop the others from being attempted.
     */
    private static void pullCategory(PriceSource source, PriceRepository repo, int categoryId,
            LocalDate snapshotDate) throws Exception {
        String runSource = "tcgcsv-" + categoryId;

        if (repo.hasSuccessfulRun(runSource, snapshotDate)) {
            System.out.printf("[%d] Already have a complete pull for %s (UTC) - skipping fetch.%n",
                    categoryId, snapshotDate);
            return;
        }

        // Throttle is shared across every category on purpose - the
        // 9000-request circuit breaker has to apply to the whole process,
        // not reset per category. requestsUsed() is therefore cumulative,
        // so this category's own count is a delta, not the running total.
        int requestsBefore = source.requestsUsed();
        long runId = repo.startRun(runSource, snapshotDate);
        int totalRows = 0;

        try {
            Category category = source.categories().stream()
                    .filter(c -> c.categoryId() == categoryId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("category " + categoryId + " not found"));
            repo.upsertCategory(category);

            List<Group> groups = source.groups(categoryId);
            System.out.printf("[%d] Found %d groups%n", categoryId, groups.size());

            for (Group g : groups) {
                repo.upsertGroup(g);

                List<Product> products = source.products(categoryId, g.groupId());
                Set<Integer> excludedInGroup = new HashSet<>();
                for (Product p : products) {
                    // Code cards are a Pokemon-specific quirk (see
                    // DEVELOPMENT_HISTORY.md) - a digital TCG Live
                    // redemption bundled in some sealed Pokemon product,
                    // not something to assume any other game also has.
                    // Other categories may turn up their own quirks later;
                    // add them the same way (excluded_products + reason)
                    // rather than folding everything into this one check.
                    if (categoryId == TcgCsvUrls.POKEMON && isCodeCard(p.name())) {
                        repo.upsertExcludedProduct(p.productId(), categoryId, p.name(), "code_card");
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

            int requestsForCategory = source.requestsUsed() - requestsBefore;
            repo.finishRun(runId, "ok", requestsForCategory, totalRows, null);
            System.out.printf("[%d] Done. %d rows written in %d requests.%n%n",
                    categoryId, totalRows, requestsForCategory);

        } catch (Exception e) {
            // Record the failure before rethrowing, so a broken run is
            // visible in the database rather than only in the console.
            int requestsForCategory = source.requestsUsed() - requestsBefore;
            repo.finishRun(runId, "failed", requestsForCategory, totalRows,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Digital code-card redemptions - a separate TCGplayer catalog listing
     * for the online code packed inside some sealed product, not a physical
     * card. Behaves nothing like a real card price-wise (see
     * DEVELOPMENT_HISTORY.md) so it's excluded everywhere, not just filtered
     * at query time. Pokemon-specific - see the call site.
     */
    private static boolean isCodeCard(String name) {
        return name != null && name.regionMatches(true, 0, "Code Card", 0, "Code Card".length());
    }
}
