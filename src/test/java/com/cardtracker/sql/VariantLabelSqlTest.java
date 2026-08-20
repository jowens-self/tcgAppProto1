package com.cardtracker.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * variant_label() and the number/qualifier helpers underneath it.
 *
 * Skipped when no Postgres is reachable - see TestDatabase.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("variant_label + number normalisation")
class VariantLabelSqlTest {

    private static TestDatabase db;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
    }

    private String label(String name) throws Exception {
        return db.scalar("SELECT variant_label(" + quote(name) + ")");
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------
    // variant_label
    // ------------------------------------------------------------------

    @Test
    void anOrdinaryPrintingHasNoLabel() throws Exception {
        assertNull(label("Charizard"));
        assertNull(label("Charizard - 4/102"));
    }

    /**
     * Rule 1. The parenthetical is the deck pilot, so rendering it as-is would
     * put a stranger's name on the card. Verified against real data in Part
     * 22: all 1,947 products of this shape are World Championship Decks.
     */
    @Test
    void aWorldChampionshipDeckIsLabelledByYearNotByPilot() throws Exception {
        assertEquals("2009 World Championship",
                label("Aaron's Collection - 2009 (Tsubasa Nakamura)"));
    }

    /** Rule 2. A parenthetical that is really a card number is not a variant. */
    @Test
    void aParenthesisedCardNumberIsNotALabel() throws Exception {
        assertNull(label("Aerodactyl (1)"));
        assertNull(label("Crobat (147)"));
        assertNull(label("Latios EX (XY72)"));
        assertNull(label("Alakazam (H1)"));
    }

    /**
     * Rule 3, and the bug behind it: a trailing bracket faces the same
     * validity test as a parenthetical, or all 26 "Unown [A]".."[Z]" get a
     * one-letter variant.
     */
    @Test
    void aSingleLetterBracketIsNotALabel() throws Exception {
        for (String letter : new String[]{"A", "M", "Z"}) {
            assertNull(label("Unown [" + letter + "]"),
                    "Unown [" + letter + "] must not be labelled");
        }
    }

    @Test
    void aGenuineQualifierIsLabelled() throws Exception {
        assertEquals("Cracked Ice Holo", label("Blastoise - 13/95 (Cracked Ice Holo)"));
        assertEquals("Ice", label("Ancient Technical Machine [Ice]"));
    }

    /**
     * The trailing card number has to come off before the qualifier is even
     * visible to a trailing match - TCGplayer writes them in that order.
     */
    @Test
    void aQualifierIsFoundBehindATrailingCardNumber() throws Exception {
        assertEquals("Professor Magnolia",
                label("Professor's Research [Professor Magnolia] - 223"));
    }

    // ------------------------------------------------------------------
    // is_variant_word: the SQL half of the mirrored rule (Part 30)
    // ------------------------------------------------------------------

    @Test
    void isVariantWordTrimsItsOwnArgument() throws Exception {
        // ::TEXT on a boolean is 'true'/'false'; psql's t/f is display only.
        assertEquals("false", db.scalar("SELECT is_variant_word(' Z ')::TEXT"));
        assertEquals("false", db.scalar("SELECT is_variant_word(E'\\tXY73\\n')::TEXT"));
        assertEquals("true", db.scalar("SELECT is_variant_word('  Cosmos Holo  ')::TEXT"));
    }

    // ------------------------------------------------------------------
    // Card numbers. Pokemon puts the number BEFORE the slash, One Piece AFTER
    // the dash, so a generic rule silently matches the set size or set code.
    // ------------------------------------------------------------------

    @Test
    void pokemonNumbersReadBeforeTheSlash() throws Exception {
        assertEquals("4", db.scalar("SELECT card_number_digits('4/102')"));
        assertEquals("4", db.scalar("SELECT card_number_digits('004/102')"),
                "leading zeros are padding");
    }

    @Test
    void onePieceNumbersReadAfterTheDash() throws Exception {
        String normalised = db.scalar("SELECT normalize_card_number('OP01-001')");
        assertTrue(normalised != null && !normalised.isBlank(),
                "OP01-001 must normalise to something usable, got: " + normalised);
    }

    /**
     * The digits-only match is allowed only when the typed number has no
     * letters, otherwise XY67a would also match XY67 - two different cards.
     */
    @Test
    void aLetterSuffixIsNotTheSameCard() throws Exception {
        assertEquals("false",
                db.scalar("SELECT (normalize_card_number('XY67a') = normalize_card_number('XY67'))::TEXT"),
                "XY67a and XY67 are different cards and must not normalise alike");
    }
}
