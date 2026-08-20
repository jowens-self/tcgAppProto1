package com.cardtracker.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * item_search(): one typed string split into a name and a card number.
 *
 * Two rules here are load-bearing and neither is obvious, which is why they
 * get tests rather than trust: the last token becomes a number only if it
 * contains a digit (otherwise "Charizard ex", "VMAX" and "GX" stop working as
 * names), and a parsed reading that returns zero rows is retried as a plain
 * name. That retry is not polish - the last-token rule genuinely guesses wrong
 * on real cards.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("item_search")
class ItemSearchSqlTest {

    private static TestDatabase db;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);
        // Search only returns products that have price history.
        for (int id : new int[]{42382, 42383, 42384, 42385, 42386, 42387, 42388, 42389,
                                479852, 900001, 900002, 900003, 99001}) {
            Fixtures.prices(db, id, "Holofoil", "10.00", "10.00");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
    }

    private String count(String query) throws Exception {
        return db.scalar("SELECT COUNT(*)::TEXT FROM item_search("
                + "'" + query.replace("'", "''") + "', NULL, TRUE, 50, 0)");
    }

    private String namesFor(String query) throws Exception {
        return db.scalar("SELECT string_agg(name || '#' || COALESCE(card_number,'-'), ', ' ORDER BY product_id) "
                + "FROM item_search('" + query.replace("'", "''") + "', NULL, TRUE, 50, 0)");
    }

    // ------------------------------------------------------------------

    @Test
    void findsByPlainName() throws Exception {
        assertTrue(Integer.parseInt(count("Charizard")) >= 2,
                "two Charizards exist in the fixture set");
    }

    /**
     * A set commonly reprints one card at several rarities across a huge price
     * range - SV03 has four Charizard ex from $5.65 to $108.58. Without these
     * fields a result list shows identical rows.
     */
    @Test
    void resultsCarryCardNumberAndRarity() throws Exception {
        String row = db.scalar("""
            SELECT card_number || '|' || rarity FROM item_search('Charizard', NULL, TRUE, 50, 0)
            WHERE product_id = 42382
            """);
        assertEquals("4/102|Holo Rare", row,
                "read from extendedData, never parsed out of the name");
    }

    @Test
    void nameAndNumberTogetherNarrowTheResult() throws Exception {
        String names = namesFor("Charizard 4");
        assertNotNull(names, "'Charizard 4' must find the 4/102 printing");
        assertTrue(names.contains("4/102"), "expected the 4/102 printing, got: " + names);
        assertTrue(!names.contains("104/102"),
                "104/102 is a different card and must not match a search for 4");
    }

    /**
     * The last token becomes a number only if it contains a digit. Without
     * this, "Charizard ex" would parse "ex" as a card number and find nothing.
     */
    @Test
    void aTrailingWordWithNoDigitStaysPartOfTheName() throws Exception {
        // 'Monkey D. Luffy' ends in a word; it must not be read as a number.
        assertEquals("1", count("Monkey D. Luffy"));
    }

    /**
     * The retry that is not optional. 'Nemona - Pikachu 42' is a real card
     * numbered 180/198, so the last-token rule parses "42" as its number and
     * finds nothing - and the whole string must then be retried as a name.
     */
    @Test
    void aParsedReadingThatFindsNothingIsRetriedAsAPlainName() throws Exception {
        assertEquals("1", count("Nemona - Pikachu 42"),
                "without the plain-name retry this real card returns nothing");
    }

    /**
     * Pokemon writes the number BEFORE the slash and One Piece AFTER the dash,
     * so a generic rule would match the set size or the set code.
     */
    @Test
    void onePieceNumbersMatchOnTheirOwnConvention() throws Exception {
        String names = namesFor("Luffy OP01-001");
        assertNotNull(names, "OP01-001 must find the Luffy leader card");
        assertTrue(names.contains("OP01-001"), "got: " + names);
    }

    /**
     * The digits-only match is allowed only when the typed number has no
     * letters, or XY67a would also match XY67 - two different cards.
     */
    @Test
    void aLetteredNumberDoesNotMatchTheBareOne() throws Exception {
        String names = namesFor("Zoro XY67a");
        assertNotNull(names);
        assertTrue(names.contains("XY67a"), "got: " + names);
        assertTrue(!names.contains("Nami"),
                "XY67a must not pull in the XY67 card, got: " + names);
    }

    @Test
    void sealedCanBeExcluded() throws Exception {
        String withSealed = db.scalar(
                "SELECT COUNT(*)::TEXT FROM item_search('Booster Box', NULL, TRUE, 50, 0)");
        String withoutSealed = db.scalar(
                "SELECT COUNT(*)::TEXT FROM item_search('Booster Box', NULL, FALSE, 50, 0)");
        assertEquals("1", withSealed);
        assertEquals("0", withoutSealed, "singles-only must drop sealed product");
    }

    @Test
    void searchCanBeScopedToOneGame() throws Exception {
        String pokemonOnly = db.scalar(
                "SELECT COUNT(*)::TEXT FROM item_search('Luffy', 3, TRUE, 50, 0)");
        assertEquals("0", pokemonOnly, "a One Piece card must not appear under Pokemon");
    }

    /**
     * One hit per product carrying variant_count - anything that puts a single
     * "price" on a multi-variant product violates the sub-type invariant.
     */
    @Test
    void oneRowPerProductNotPerVariant() throws Exception {
        db.exec("""
            INSERT INTO price_snapshots (product_id, sub_type_name, snapshot_date,
                                         market_price, low_price, mid_price, high_price)
            VALUES (42382, 'Reverse Holofoil', DATE '%s', 77.00, 77.00, 77.00, 99999.00)
            """.formatted(Fixtures.TODAY));

        String rows = db.scalar(
                "SELECT COUNT(*)::TEXT FROM item_search('Charizard', NULL, TRUE, 50, 0) "
                + "WHERE product_id = 42382");
        assertEquals("1", rows, "two variants must still be one search result");

        String variants = db.scalar(
                "SELECT variant_count::TEXT FROM item_search('Charizard', NULL, TRUE, 50, 0) "
                + "WHERE product_id = 42382");
        assertEquals("2", variants, "the row must report how many variants exist");
    }
}
