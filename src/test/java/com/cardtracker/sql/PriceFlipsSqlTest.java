package com.cardtracker.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Flipping prices: which cards refresh_price_flips() flags, and that trends
 * leave them out unless asked while values never do.
 *
 * The cast, 120 days each, ending at Fixtures.TODAY, in one old set:
 *   990501  flips: $100, then $10 and back twice, five days at a time
 *   990502  one excursion: $50, five days at $5, back to $50
 *   990503  one-day spikes: $40, with three single days at $400
 *   990504  a real step: $10, then $30 for good
 *   990505  wiggles: $100 and $60 in turn, never 2x apart
 * Only the first flips. The first two both read +900% over the last week,
 * so the board shows whether the rule, and only the rule, decides.
 *
 * And in a cheap set of their own, flipping the same way as 990501:
 *   990511  between $0.20 and $0.60, never reaching $1
 *   990512  between $0.40 and exactly $1.00
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("flipping prices")
class PriceFlipsSqlTest {

    private static TestDatabase db;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);
        db.exec("""
            INSERT INTO groups (group_id, category_id, name, published_on) VALUES
                (990500, 3, 'Old Set', DATE '%s' - 400);
            INSERT INTO products (product_id, group_id, category_id, name, is_sealed) VALUES
                (990501, 990500, 3, 'Flipper', FALSE),
                (990502, 990500, 3, 'One Excursion', FALSE),
                (990503, 990500, 3, 'Spiky', FALSE),
                (990504, 990500, 3, 'Stepper', FALSE),
                (990505, 990500, 3, 'Wiggler', FALSE);
            INSERT INTO groups (group_id, category_id, name, published_on) VALUES
                (990510, 3, 'Cheap Set', DATE '%s' - 400);
            INSERT INTO products (product_id, group_id, category_id, name, is_sealed) VALUES
                (990511, 990510, 3, 'Penny Flipper', FALSE),
                (990512, 990510, 3, 'Dollar Flipper', FALSE);
            """.formatted(Fixtures.TODAY, Fixtures.TODAY));

        Fixtures.prices(db, 990501, "Holofoil",
                series(100, "100.00", 5, "10.00", 5, "100.00", 5, "10.00", 5, "100.00"));
        Fixtures.prices(db, 990502, "Holofoil", series(110, "50.00", 5, "5.00", 5, "50.00"));
        String[] spiky = series(120, "40.00");
        for (int i : new int[] {60, 80, 100}) spiky[i] = "400.00";
        Fixtures.prices(db, 990503, "Holofoil", spiky);
        Fixtures.prices(db, 990504, "Holofoil", series(60, "10.00", 60, "30.00"));
        Fixtures.prices(db, 990505, "Holofoil", series(
                20, "100.00", 20, "60.00", 20, "100.00", 20, "60.00", 20, "100.00", 20, "60.00"));
        Fixtures.prices(db, 990511, "Holofoil",
                series(100, "0.60", 5, "0.20", 5, "0.60", 5, "0.20", 5, "0.60"));
        Fixtures.prices(db, 990512, "Holofoil",
                series(100, "1.00", 5, "0.40", 5, "1.00", 5, "0.40", 5, "1.00"));

        assertEquals("2", db.scalar("SELECT refresh_price_flips()::TEXT"));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
    }

    /** Pairs of (days, price), oldest first. */
    private static String[] series(Object... runs) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < runs.length; i += 2) {
            for (int d = 0; d < (int) runs[i]; d++) out.add((String) runs[i + 1]);
        }
        return out.toArray(new String[0]);
    }

    private static String gainsBoard(String extraArgs) throws Exception {
        return db.scalar("""
            SELECT string_agg(product_id || CASE WHEN flipping THEN '*' ELSE '' END, ','
                              ORDER BY product_id)
            FROM item_movers(7, 3, 990500, FALSE, 1, 'up', 3, 50, DATE '%s'%s)
            WHERE pct_change > 100
            """.formatted(Fixtures.TODAY, extraArgs));
    }

    // ------------------------------------------------------------------

    @Test
    void onlyTheCardThatKeepsGoingBackIsFlagged() throws Exception {
        assertEquals("990501|10.00|100.00|3", db.scalar("""
            SELECT string_agg(product_id || '|' || level_low || '|' || level_high || '|' || reversals, ',')
            FROM price_flips WHERE product_id < 990510
            """), "two trips out and back is three returns to a level it had left");
    }

    /** One that never reaches $1 is under Movers' floor either way and moves a set by cents. */
    @Test
    void aFlipperIsOnlyFlaggedOnceItsHigherPriceIsADollar() throws Exception {
        assertEquals("990512|0.40|1.00", db.scalar("""
            SELECT string_agg(product_id || '|' || level_low || '|' || level_high, ',')
            FROM price_flips WHERE product_id > 990510
            """));
    }

    /** The hourly job calls it every run; only a new day costs a rebuild. */
    @Test
    void aSecondRefreshOnTheSameDayDoesNothing() throws Exception {
        assertNull(db.scalar("SELECT refresh_price_flips()::TEXT"));
    }

    @Test
    void moversLeavesFlippingPricesOutUntilAskedAndMarksThem() throws Exception {
        assertEquals("990502", gainsBoard(""),
                "the one-off excursion is a move; the flipper is not on the board");
        assertEquals("990501*,990502", gainsBoard(", p_include_flipping => TRUE"),
                "asked for, it is back, and marked");
    }

    /** What a set is worth counts every card; only its change leaves the flipper out. */
    @Test
    void aSetCountsTheFlipperInItsValueButNotItsChange() throws Exception {
        String sql = """
            SELECT n_priced || '|' || n_common || '|' || n_flipping
            FROM game_set_overview(3, 7, DATE '%s'%s) WHERE group_id = 990500
            """;
        assertEquals("5|4|1", db.scalar(sql.formatted(Fixtures.TODAY, "")));
        assertEquals("5|5|0", db.scalar(sql.formatted(Fixtures.TODAY,
                ", p_include_flipping => TRUE")));
    }

    /** The card view says so, with the two levels. */
    @Test
    void theVariantCarriesItsTwoLevels() throws Exception {
        assertEquals("10.00|100.00|3", db.scalar("""
            SELECT flip_low || '|' || flip_high || '|' || flip_reversals FROM item_variants(990501)
            """));
        assertNull(db.scalar("SELECT flip_low::TEXT FROM item_variants(990502)"));
    }
}
