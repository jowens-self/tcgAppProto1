package com.cardtracker.images;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matcher's string rules, which is where this project's real defects have
 * concentrated (Parts 21, 22, 25, 26, 30).
 *
 * Every case below is a printing that exists in the catalog, not an invented
 * edge case. That matters: the rules were each added because a specific real
 * card broke something, so a test that drifts to synthetic inputs stops
 * defending the thing it was written for.
 */
class ImportCardImagesTest {

    @Nested
    @DisplayName("isVariantWord")
    class IsVariantWord {

        /**
         * The length floor. Without it every one of "Unown [A]".."[Z]" reads
         * as a printing variant - 26 cards labelled with a single letter.
         */
        @ParameterizedTest
        @ValueSource(strings = {"A", "M", "Z"})
        void singleLettersAreNotVariants(String token) {
            assertFalse(ImportCardImages.isVariantWord(token),
                    "Unown [" + token + "] would be labelled a variant");
        }

        /**
         * The card-code test. These all appear in product names as
         * parentheticals and are card NUMBERS, not printings.
         */
        @ParameterizedTest
        @ValueSource(strings = {"1", "2", "147", "XY72", "XY73", "SV66", "H1", "SWSH014", "RC4", "TG12"})
        void cardCodesAreNotVariants(String token) {
            assertFalse(ImportCardImages.isVariantWord(token),
                    token + " is a card number, not a printing");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Cracked Ice Holo", "Cosmos Holo", "Delta Species", "Team Plasma",
                "Prerelease", "Staff", "Professor Magnolia", "Ice"})
        void realQualifiersAreVariants(String token) {
            assertTrue(ImportCardImages.isVariantWord(token));
        }

        /**
         * Trims internally rather than trusting callers to do it. Its SQL
         * mirror relied on caller discipline until 2026-08-20, and 81 of
         * 2,547 real tokens changed verdict when padded - every one of them
         * re-opening one of the two bugs above. See Part 30.
         */
        @Test
        void trimsItsOwnArgument() {
            assertFalse(ImportCardImages.isVariantWord(" Z "));
            assertFalse(ImportCardImages.isVariantWord("\tXY73\n"));
            assertTrue(ImportCardImages.isVariantWord("  Cosmos Holo  "));
        }

        @Test
        void nullAndBlankAreNotVariants() {
            assertFalse(ImportCardImages.isVariantWord(null));
            assertFalse(ImportCardImages.isVariantWord(""));
            assertFalse(ImportCardImages.isVariantWord("   "));
        }
    }

    @Nested
    @DisplayName("numberDigits")
    class NumberDigits {

        /**
         * Part 21: an anchored leading-digits regex returned null for every
         * number not starting with a digit, which left 1,550 of 20,479
         * catalog cards unindexed and their products never even attempting a
         * match. These are the shapes that were being dropped.
         */
        @Test
        void findsDigitsInCodesThatDoNotStartWithOne() {
            assertEquals("1", ImportCardImages.numberDigits("H1"));
            assertEquals("66", ImportCardImages.numberDigits("SV066"));
            assertEquals("14", ImportCardImages.numberDigits("SWSH014"));
            assertEquals("4", ImportCardImages.numberDigits("RC4"));
            assertEquals("12", ImportCardImages.numberDigits("TG12"));
            assertEquals("46", ImportCardImages.numberDigits("GG46"));
        }

        /**
         * Leading zeros are padding, not value - so the printed "004/102"
         * matches a catalog card numbered 4. Dropping them by string rather
         * than parseInt is deliberate: a pathological run of digits would
         * overflow an int, and the numeric value is never wanted.
         */
        @Test
        void treatsLeadingZerosAsPadding() {
            assertEquals("4", ImportCardImages.numberDigits("4"));
            assertEquals("4", ImportCardImages.numberDigits("004"));
            assertEquals("4", ImportCardImages.numberDigits("004/102"));
        }

        /** The denominator is the set size and must never be read as the number. */
        @Test
        void readsTheNumberBeforeTheSlashNotAfter() {
            assertEquals("4", ImportCardImages.numberDigits("4/102"));
            assertEquals("102", ImportCardImages.numberTotal("4/102"));
            assertNull(ImportCardImages.numberTotal("SWSH014"));
        }

        @Test
        void returnsNullWhenThereAreNoDigits() {
            assertNull(ImportCardImages.numberDigits("ABC"));
            assertNull(ImportCardImages.numberDigits(null));
        }
    }

    @Nested
    @DisplayName("numberCode")
    class NumberCode {

        /**
         * The prefix is the whole point: it is what tells Aquapolis' holo
         * "H2" apart from every other card numbered 2, after the printed
         * totals (32 vs 147) have already ruled out the strict key.
         */
        @Test
        void keepsTheAlphabeticPrefix() {
            assertEquals("h2", ImportCardImages.numberCode("H02/H32"));
            assertEquals("bw64", ImportCardImages.numberCode("BW64"));
            assertEquals("swsh39", ImportCardImages.numberCode("SWSH039"));
            assertEquals("sh6", ImportCardImages.numberCode("SH6"));
            assertEquals("hgss20", ImportCardImages.numberCode("HGSS20"));
        }

        /**
         * Null, not the bare digits. Without a prefix this key would be
         * character-for-character the loose key, so a non-null answer would
         * add a tier that re-decides what the loose tier already decides -
         * and would report a different rule name for the same match.
         */
        @Test
        void isNullWhenTheNumberHasNoPrefix() {
            assertNull(ImportCardImages.numberCode("4"));
            assertNull(ImportCardImages.numberCode("004/102"));
            assertNull(ImportCardImages.numberCode("16"));
            assertNull(ImportCardImages.numberCode(null));
            assertNull(ImportCardImages.numberCode("ABC"));
        }

        /** Padding is dropped on this side too, or "H02" misses the catalog's "H2". */
        @Test
        void treatsLeadingZerosAsPadding() {
            assertEquals("h2", ImportCardImages.numberCode("H02"));
            assertEquals("h2", ImportCardImages.numberCode("H2"));
            assertEquals("h10", ImportCardImages.numberCode("H10"));
            assertEquals("h1", ImportCardImages.numberCode("H01"));
        }

        /** The denominator is the subset size, never the number. */
        @Test
        void readsTheNumberBeforeTheSlashNotAfter() {
            assertEquals("h2", ImportCardImages.numberCode("H02/H32"));
            assertEquals("tg12", ImportCardImages.numberCode("TG12/TG30"));
        }

        /**
         * Bounded at five letters so a word can never be read as a prefix.
         * The real prefixes are 1-4 ("H", "GG", "SWSH"); the bound is what
         * stops a malformed number contributing a junk key to the index.
         */
        @Test
        void refusesAPrefixTooLongToBeOne() {
            assertNull(ImportCardImages.numberCode("promotional1"));
        }

        /**
         * Every card this keys is also keyed by numberDigits, which is what
         * makes the tier a narrowing of the loose one rather than a widening
         * - it can turn an ambiguous candidate list into a single hit, and can
         * never introduce a candidate the loose key did not already hold.
         */
        @Test
        void isAlwaysANarrowingOfTheLooseKey() {
            for (String n : new String[]{"H02/H32", "BW64", "SWSH039", "SH6", "HGSS20"}) {
                String code = ImportCardImages.numberCode(n);
                assertNotNull(code, n);
                assertTrue(code.endsWith(ImportCardImages.numberDigits(n)),
                        n + " -> " + code + " must end in its own digits");
            }
        }
    }

    @Nested
    @DisplayName("normName")
    class NormName {

        @Test
        void stripsTrailingCardNumbers() {
            assertEquals("mew ex", ImportCardImages.normName("Mew ex - 232/091"));
            assertEquals("rillaboom v", ImportCardImages.normName("Rillaboom V - SWSH014"));
        }

        /**
         * A genuine qualifier is KEPT here - the catalog usually carries it
         * too, and keeping it is what resolves the card to its own artwork
         * rather than the base printing's.
         */
        @Test
        void keepsGenuineQualifiers() {
            assertTrue(ImportCardImages.normName("Blastoise - 13/95 (Cracked Ice Holo)")
                    .contains("cracked ice holo"));
        }

        /**
         * But a parenthesised card number is still noise. "Aerodactyl (1)" is
         * simply Fossil #1; the dry run caught this being treated as a
         * printing in the first version of the tier-2 rule.
         */
        @Test
        void stripsParenthesisedCardNumbers() {
            assertEquals("aerodactyl", ImportCardImages.normName("Aerodactyl (1)"));
        }
    }

    @Nested
    @DisplayName("strippedQualifier")
    class StrippedQualifier {

        @Test
        void reportsTheWordThatWouldBeDropped() {
            assertEquals("cracked ice holo",
                    ImportCardImages.strippedQualifier("Blastoise - 13/95 (Cracked Ice Holo)"));
        }

        /**
         * Records verbatim, deliberately. TCGplayer sells both a
         * "Semi-Finalist" and a "Semi Finalist" printing, so folding them
         * here would destroy a true fact about the catalog to tidy a lookup
         * table. Spelling is reconciled at comparison time by
         * normalize_qualifier() instead. Part 27.
         */
        @Test
        void keepsSpellingVariantsDISTINCT() {
            String hyphen = ImportCardImages.strippedQualifier("Fake Card (Semi-Finalist)");
            String space = ImportCardImages.strippedQualifier("Fake Card (Semi Finalist)");
            assertEquals("semi-finalist", hyphen);
            assertEquals("semi finalist", space);
            assertNotEquals(hyphen, space,
                    "folding these here would destroy a true fact about the catalog");
        }

        /**
         * The number comes off before the qualifier is visible to a trailing
         * match - TCGplayer writes the two in that order.
         */
        @Test
        void seesAQualifierSittingBehindACardNumber() {
            assertEquals("professor magnolia",
                    ImportCardImages.strippedQualifier("Professor's Research [Professor Magnolia] - 223"));
        }

        @Test
        void returnsNullForAnOrdinaryPrinting() {
            assertNull(ImportCardImages.strippedQualifier("Charizard - 4/102"));
            assertNull(ImportCardImages.strippedQualifier("Aerodactyl (1)"));
        }
    }
}
