package com.cardtracker.api;

import com.cardtracker.sql.Fixtures;
import com.cardtracker.sql.TestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response contract, over real HTTP against a throwaway database.
 *
 * The routing is thin; the contract is not. Three of these encode rules that
 * exist because something went wrong, and one of them caught a live bug the
 * day it was written.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("API contract")
class ApiContractTest {

    private static TestDatabase db;
    private static Javalin app;
    private static HttpClient http;
    private static String base;
    private static Path imageRoot;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);
        Fixtures.prices(db, 42382, "Holofoil", "100.00", "110.00", "120.00");
        Fixtures.prices(db, 42382, "Reverse Holofoil", "70.00", "70.00", "77.00");

        imageRoot = Files.createTempDirectory("ct-images-test");
        // ImportCardImages names every file <category_id>/<product_id>.jpg,
        // and the endpoint resolves it from the id rather than from the row.
        Files.createDirectories(imageRoot.resolve("3"));
        Files.write(imageRoot.resolve("3/42382.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        db.exec("""
            INSERT INTO card_images (product_id, source_id, status, file_path, bytes)
            VALUES (42382, 'base1-4', 'ok', '3/42382.jpg', 5)
            """);

        app = ApiServer.createApp(db.url(), db.user(), db.pass(), imageRoot);
        app.start(0);
        base = "http://localhost:" + app.port() + "/api";
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (app != null) app.stop();
        if (db != null) db.close();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------
    // Holdings: the only writing endpoints.
    // ------------------------------------------------------------------

    /**
     * A holding recorded against a variant the card does not have can never be
     * priced. It does not error, it just sits in the collection at null value
     * forever, which reads as a bug in the app rather than a typo in the entry.
     * The front end prevents it by offering only real printings; this is the
     * backstop, and it must also say what IS valid or the caller is stuck.
     */
    @Test
    void aVariantTheCardDoesNotHaveIsRefusedByName() throws Exception {
        HttpResponse<String> r = post("/holdings",
                """
                {"productId":42382,"subTypeName":"Nonexistent Foil","quantity":1}
                """);
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("Holofoil"),
                "the refusal must name the variants the card does trade as, or the "
                + "caller has no way forward. Got: " + r.body());
    }

    @Test
    void anUnknownProductIsRefused() throws Exception {
        assertEquals(400, post("/holdings",
                """
                {"productId":999999999,"subTypeName":"Holofoil","quantity":1}
                """).statusCode());
    }

    @Test
    void anUnreadableHoldingIsTheCallersMistake() throws Exception {
        assertEquals(400, post("/holdings",
                """
                {"productId":42382,"subTypeName":"Holofoil","quantity":1,"snapshotDate":"soon"}
                """).statusCode(), "a date that doesn't parse is a 400, not a 500");
    }

    /**
     * Recording is append-only: a correction adds a row and the newest wins,
     * and quantity 0 is a sale rather than a delete. The history endpoint is
     * what makes that checkable, and it regressed once already when the SQL
     * moved into a function and the column names changed underneath it.
     */
    @Test
    void correctingAQuantityAppendsRatherThanReplaces() throws Exception {
        assertEquals(201, post("/holdings",
                """
                {"productId":42382,"subTypeName":"Holofoil","quantity":2,"costBasis":5.00}
                """).statusCode());
        assertEquals(201, post("/holdings",
                """
                {"productId":42382,"subTypeName":"Holofoil","quantity":7,"costBasis":5.00}
                """).statusCode());

        String current = get("/holdings").body();
        assertTrue(current.contains("\"quantity\":7"),
                "the newest entry must win. Got: " + current);

        String history = get("/holdings/42382/history").body();
        assertTrue(history.contains("\"quantity\":7") && history.contains("\"quantity\":2"),
                "both entries must survive - overwriting is the one thing append-only "
                + "exists to prevent. Got: " + history);
        assertTrue(history.contains("recordedOn") && history.contains("enteredAt"),
                "history must carry the day it is true from AND when it was typed, "
                + "since the latter orders two changes on one day. Got: " + history);
    }

    /**
     * The chart's final point must equal the total on the screen above it.
     *
     * collection_value() and current_holdings() are two definitions of "what
     * this collection is worth", one per day and one for now, and nothing but
     * this test stops them drifting. If they disagree the page shows a chart
     * that contradicts the headline number directly above it - worse than no
     * chart. Both deliberately use market_price with carry-forward for exactly
     * this reason; a fallback to mid/low on either side alone would break it.
     *
     * Posts its own holding so the comparison is never 0 == 0, and compares
     * whatever the database holds at that moment, so it does not depend on
     * which other holdings tests happened to run first.
     */
    @Test
    void chartEndsOnTheSameValueAsTheTotal() throws Exception {
        assertEquals(201, post("/holdings",
                """
                {"productId":42382,"subTypeName":"Reverse Holofoil","quantity":3,"costBasis":50.00}
                """).statusCode());

        ObjectMapper json = new ObjectMapper();
        HttpResponse<String> series = get("/holdings/value?days=30");
        assertEquals(200, series.statusCode(), series.body());
        JsonNode points = json.readTree(series.body());
        assertEquals(30, points.size(), "one point per day in the window");

        BigDecimal chartLast = points.get(points.size() - 1).get("value").decimalValue();
        BigDecimal tableTotal = BigDecimal.ZERO;
        for (JsonNode h : json.readTree(get("/holdings").body())) {
            if (!h.get("positionValue").isNull()) {
                tableTotal = tableTotal.add(h.get("positionValue").decimalValue());
            }
        }
        assertTrue(tableTotal.signum() > 0, "nothing to compare - the holding did not price");
        assertEquals(0, chartLast.compareTo(tableTotal),
                "chart ends at " + chartLast + " but the table totals " + tableTotal);
    }

    @Test
    void valueWindowIsBounded() throws Exception {
        // One point is not a line.
        assertEquals(400, get("/holdings/value?days=1").statusCode());
        // Cost grows with the window, and past a few hundred days the extra
        // points are sub-pixel anyway, so it refuses rather than grinding.
        assertEquals(400, get("/holdings/value?days=2000").statusCode());
    }

    /**
     * The "what you paid" line ends on the Cost total, for the same reason the
     * value line ends on the Value total. The table counts quantity x cost for
     * holdings with a cost and leaves the rest out; the chart must do the same
     * or the two lines on one screen tell different stories.
     *
     * Adds a holding with NO cost, so the comparison would catch a chart that
     * counted it as free, or a table that stopped skipping it.
     */
    @Test
    void chartCostEndsOnTheCostTotal() throws Exception {
        assertEquals(201, post("/holdings",
                """
                {"productId":42382,"subTypeName":"Holofoil","condition":"Lightly Played","quantity":4,"costBasis":12.50}
                """).statusCode());
        assertEquals(201, post("/holdings",
                """
                {"productId":42382,"subTypeName":"Holofoil","condition":"Damaged","quantity":1}
                """).statusCode());

        ObjectMapper json = new ObjectMapper();
        JsonNode points = json.readTree(get("/holdings/value?days=30").body());
        JsonNode last = points.get(points.size() - 1);

        BigDecimal tableCost = BigDecimal.ZERO;
        int tableUncosted = 0;
        for (JsonNode h : json.readTree(get("/holdings").body())) {
            if (h.get("costBasis").isNull()) {
                tableUncosted++;
            } else {
                tableCost = tableCost.add(
                        h.get("costBasis").decimalValue().multiply(BigDecimal.valueOf(h.get("quantity").asInt())));
            }
        }
        assertTrue(tableCost.signum() > 0, "nothing to compare - no holding had a cost");
        assertEquals(0, last.get("cost").decimalValue().compareTo(tableCost),
                "chart's paid line ends at " + last.get("cost") + " but the table's cost is " + tableCost);
        assertEquals(tableUncosted, last.get("uncosted").asInt(),
                "the chart must count the same uncosted holdings the table leaves out");
        assertTrue(tableUncosted > 0, "the uncosted holding should be counted, not priced at $0");
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** No row yet means the defaults; a save round-trips; the next read sees it. */
    @Test
    void settingsStartAtTheDefaultsAndSave() throws Exception {
        try {
            assertEquals("""
                    {"showCostLine":false,"includePreRelease":false,"includeEarly":false,"includeFlipping":false}""",
                    get("/settings").body());

            HttpResponse<String> saved = put("/settings",
                    """
                    {"showCostLine":true,"includePreRelease":false,"includeEarly":true,"includeFlipping":true}
                    """);
            assertEquals(200, saved.statusCode(), saved.body());
            assertEquals("""
                    {"showCostLine":true,"includePreRelease":false,"includeEarly":true,"includeFlipping":true}""",
                    get("/settings").body());
        } finally {
            put("/settings", """
                    {"showCostLine":false,"includePreRelease":false,"includeEarly":false,"includeFlipping":false}
                    """);
        }
    }

    /** A missing field would have to be guessed, and a guess changes a preference. */
    @Test
    void aPartialSettingsBodyIsRefused() throws Exception {
        assertEquals(400, put("/settings", """
                {"showCostLine":true}
                """).statusCode());
        assertEquals(400, put("/settings", """
                {"showCostLine":true,"includePreRelease":false,"includeEarly":true}
                """).statusCode(), "a client from before includeFlipping must not reset it");
        assertEquals(400, put("/settings", "not json").statusCode(),
                "an unreadable body is the caller's mistake, so a 400, not a 500");
    }

    /**
     * Moving a game's modern cutoff changes what counts, and the answer comes
     * from the same rule every screen uses. The fixtures' Pokemon sets are
     * dated 1999, 1999, 2018 and 2023, so the Sword & Shield cutoff takes in
     * one of them and no cutoff takes in all four.
     */
    @Test
    void gameRulesChangeWhatCountsAsModern() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try {
            JsonNode everything = json.readTree(put("/games/3/rules",
                    """
                    {"modernSince":null,"earlyDays":70}
                    """).body());
            assertTrue(everything.get("modernSince").isNull());
            assertEquals(70, everything.get("earlyDays").asInt());
            assertEquals(everything.get("setCount").asInt(), everything.get("modernSetCount").asInt(),
                    "with no cutoff, every set is modern");

            JsonNode swsh = json.readTree(put("/games/3/rules",
                    """
                    {"modernSince":"2019-11-15","earlyDays":56}
                    """).body());
            assertEquals("2019-11-15", swsh.get("modernSince").asText());
            assertEquals(1, swsh.get("modernSetCount").asInt(), "only 151 (2023) is after 2019-11-15");
        } finally {
            put("/games/3/rules", """
                    {"modernSince":"2019-11-15","earlyDays":56}
                    """);
        }
    }

    @Test
    void badGameRulesAreRefused() throws Exception {
        assertEquals(400, put("/games/3/rules", """
                {"modernSince":"2019-11-15","earlyDays":-1}
                """).statusCode(), "a negative early period");
        assertEquals(400, put("/games/3/rules", """
                {"modernSince":"2019-11-15"}
                """).statusCode(), "no early period at all");
        assertEquals(400, put("/games/3/rules", """
                {"modernSince":"not a date","earlyDays":56}
                """).statusCode(), "a date that isn't one");
        assertEquals(404, put("/games/999/rules", """
                {"modernSince":null,"earlyDays":56}
                """).statusCode(), "a game that isn't tracked");
    }

    // ------------------------------------------------------------------

    @Test
    void healthIsUp() throws Exception {
        assertEquals(200, get("/health").statusCode());
    }

    /**
     * Health must answer "is data still arriving", not "is this process up".
     *
     * Part 40: the hourly pull failed every hour for five days and nothing
     * noticed, because a job that cannot reach its own repository exits
     * without writing anything and an empty log is indistinguishable from a
     * quiet success. A liveness check that returns ok while the history has
     * silently stopped is the exact shape of that failure, so it is worth a
     * test rather than a convention.
     *
     * The fixtures are anchored at Fixtures.TODAY (2026-08-19) and time only
     * moves forward, so this data is permanently more than two days old. That
     * makes the STALE path - the one that actually matters and the one that
     * would otherwise never be exercised - the one this asserts.
     */
    @Test
    void healthReportsStaleDataRatherThanJustOk() throws Exception {
        String body = get("/health").body();
        assertTrue(body.contains("\"status\":\"stale\""),
                "health must report stale when the newest snapshot is older than the "
                + "threshold; fixtures are dated " + com.cardtracker.sql.Fixtures.TODAY
                + " and can only get older. Got: " + body);
        assertTrue(body.contains("newestSnapshot"),
                "health must name the newest snapshot it has, so 'stale' is checkable "
                + "rather than something to take on trust. Got: " + body);
        assertTrue(body.contains("daysStale"),
                "health must say HOW stale, since one day behind is by design and "
                + "several is a broken job. Got: " + body);
    }

    /**
     * The structural version of "store it, ignore it". Sellers price-park, so
     * high_price is noise dressed as data, and PricePoint has no field for it
     * precisely so a future chart author cannot reach for it by accident. The
     * fixtures park it at 99,999, so a leak would be unmissable here.
     */
    @Test
    void noEndpointEverReturnsHighPrice() throws Exception {
        String[] paths = {
                "/games",
                "/items/search?q=Charizard&limit=10",
                "/items/42382",
                "/items/42382/variants",
                "/items/42382/history?subType=Holofoil",
                "/movers?days=1&limit=10",
                "/sets/overview?days=7",
        };
        for (String path : paths) {
            String body = get(path).body();
            assertFalse(body.contains("highPrice") || body.contains("high_price"),
                    path + " leaked a high price field");
            assertFalse(body.contains("99999"),
                    path + " leaked the parked high price value");
        }
    }

    /**
     * A set commonly reprints one card at several rarities across a huge price
     * range, so a result list without these shows identical rows.
     */
    @Test
    void searchResultsCarryIdentityFields() throws Exception {
        String body = get("/items/search?q=Charizard&limit=10").body();
        assertTrue(body.contains("\"cardNumber\":\"4/102\""), body);
        assertTrue(body.contains("\"rarity\":\"Holo Rare\""), body);
        // The sole claimant of its picture: its own art, so no warning.
        assertTrue(body.contains("\"artBorrowed\":false"), body);
    }

    /** One hit per product carrying variantCount, never one row per variant. */
    @Test
    void searchReturnsOneRowPerProduct() throws Exception {
        String body = get("/items/search?q=Charizard&limit=10").body();
        int occurrences = body.split("\"productId\":42382", -1).length - 1;
        assertEquals(1, occurrences, "two variants must still be one result row: " + body);
        assertTrue(body.contains("\"variantCount\":2"), body);
    }

    /**
     * subType has no default deliberately: picking one would mean guessing
     * which market the caller meant, and Normal / Holofoil / Reverse Holofoil
     * are genuinely different markets.
     */
    @Test
    void historyRequiresAnExplicitSubType() throws Exception {
        assertEquals(400, get("/items/42382/history").statusCode(),
                "a missing subType must be a bad request, not a guess");
        assertEquals(200, get("/items/42382/history?subType=Holofoil").statusCode());
    }

    @Test
    void unknownProductsAre404NotServerErrors() throws Exception {
        assertEquals(404, get("/items/99999999").statusCode());
        assertEquals(404, get("/items/99999999/variants").statusCode());
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    @Test
    void aCachedImageIsServedFromOurOwnDisk() throws Exception {
        HttpResponse<String> res = get("/items/42382/image");
        assertEquals(200, res.statusCode());
        assertEquals("image/jpeg", res.headers().firstValue("Content-Type").orElse(""));
    }

    /**
     * "No image" is an ordinary outcome, not an error. Roughly 40% of Pokemon
     * singles have no upstream image and One Piece has none at all.
     */
    @Test
    void aProductWithNoImageIs404() throws Exception {
        assertEquals(404, get("/items/42383/image").statusCode());
    }

    /**
     * The stored file_path is not read at all any more, which is what removed
     * the traversal vector rather than a check catching it: the served path is
     * built from an int and a directory we listed ourselves. A row pointing
     * outside the image root is simply ignored, so this still asserts the
     * outcome that matters even though the mechanism changed.
     */
    @Test
    void aCraftedImagePathCannotEscapeTheImageRoot() throws Exception {
        Path secret = imageRoot.getParent().resolve("ct-secret.txt");
        Files.writeString(secret, "must not be served");
        db.exec("""
            INSERT INTO card_images (product_id, source_id, status, file_path, bytes)
            VALUES (42384, 'x-1', 'ok', '../ct-secret.txt', 5)
            """);

        HttpResponse<String> res = get("/items/42384/image");
        assertEquals(404, res.statusCode(), "traversal must be refused");
        assertFalse(res.body().contains("must not be served"), "file contents leaked");

        Files.deleteIfExists(secret);
    }

    // ------------------------------------------------------------------
    // Provenance
    // ------------------------------------------------------------------

    /**
     * The bug this caught for real. owner_product_id is null for every own_art
     * row, and the null test had been written inline in the record constructor
     * several get() calls after the column it described, so it reported on
     * `provenance` instead and would have turned every absent owner into
     * product 0.
     */
    @Test
    void provenanceReportsAnAbsentOwnerAsNullNotZero() throws Exception {
        String body = get("/items/42382/image/provenance").body();
        assertTrue(body.contains("\"provenance\":\"own_art\""), body);
        assertTrue(body.contains("\"ownerProductId\":null"),
                "an absent owner must be null, never 0: " + body);
        assertFalse(body.contains("\"ownerProductId\":0"), body);
    }

    @Test
    void provenanceIs404WhenWeHoldNoImage() throws Exception {
        assertEquals(404, get("/items/42383/image/provenance").statusCode());
    }
}
