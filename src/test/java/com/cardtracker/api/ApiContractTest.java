package com.cardtracker.api;

import com.cardtracker.sql.Fixtures;
import com.cardtracker.sql.TestDatabase;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

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
