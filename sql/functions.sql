-- CardTracker query functions (v1)
--
-- Formalizes the analytical building blocks worked out by hand in
-- DEVELOPMENT_HISTORY.md Part 12, as real, reusable, parameterized SQL
-- rather than hand-typed queries repeated per question.
--
-- Hard rule baked in here, not left as caller-adjustable: a set's price or
-- return is defined ONLY over its singles (is_sealed = FALSE). Sealed
-- product is a fully separate category - it's trackable individually via
-- item_return()/item_all_time_high() same as any product, but it never
-- contributes to a set-level aggregate. See CLAUDE.md.

-- Return for one specific product+variant between two exact dates - a
-- card OR a sealed item, deliberately, since the math is identical either
-- way and a product_id already has a fixed category the moment you pick
-- it. Named item_*, not card_*, and returns name/is_sealed alongside the
-- numbers specifically so the category is always visible in the result
-- rather than something the caller has to already know or look up
-- separately. Empty result (no row) if either date has no recorded price
-- for that item - deliberately not "nearest available date," so a caller
-- never silently gets a different day than the one they asked for.
CREATE OR REPLACE FUNCTION item_return(
    p_product_id INTEGER,
    p_sub_type_name TEXT,
    p_start_date DATE,
    p_end_date DATE
) RETURNS TABLE (
    name TEXT,
    is_sealed BOOLEAN,
    start_price NUMERIC,
    end_price NUMERIC,
    pct_return NUMERIC
) AS $$
    SELECT
        p.name,
        p.is_sealed,
        s.market_price,
        e.market_price,
        CASE WHEN s.market_price > 0
             THEN ROUND(((e.market_price - s.market_price) / s.market_price) * 100, 2)
             ELSE NULL END
    FROM products p,
        (SELECT market_price FROM price_snapshots
          WHERE product_id = p_product_id AND sub_type_name = p_sub_type_name
            AND snapshot_date = p_start_date AND market_price IS NOT NULL) s,
        (SELECT market_price FROM price_snapshots
          WHERE product_id = p_product_id AND sub_type_name = p_sub_type_name
            AND snapshot_date = p_end_date AND market_price IS NOT NULL) e
    WHERE p.product_id = p_product_id;
$$ LANGUAGE sql STABLE;

-- All-time high for one product+variant, card or sealed - same naming and
-- visibility reasoning as item_return above. Uses the market->mid->low
-- fallback (see PriceSnapshot.trendablePrice / CLAUDE.md "Data invariants")
-- since this is a MAX() over the whole history, not a two-point comparison -
-- the anchor-bias risk from Part 12 doesn't apply here the same way.
CREATE OR REPLACE FUNCTION item_all_time_high(
    p_product_id INTEGER,
    p_sub_type_name TEXT
) RETURNS TABLE (
    name TEXT,
    is_sealed BOOLEAN,
    all_time_high NUMERIC
) AS $$
    SELECT p.name, p.is_sealed, MAX(COALESCE(ps.market_price, ps.mid_price, ps.low_price))
    FROM price_snapshots ps
    JOIN products p ON p.product_id = ps.product_id
    WHERE ps.product_id = p_product_id AND ps.sub_type_name = p_sub_type_name
    GROUP BY p.name, p.is_sealed;
$$ LANGUAGE sql STABLE;

-- Portfolio-style set return: sum every single card's price on start_date,
-- sum every single card's price on end_date, return the % change between
-- the two totals. Restricted to the (product, variant) basket present with
-- a real market_price on BOTH dates, so a coverage change between the two
-- days (tcgcsv tracking more/fewer products) never masquerades as a price
-- move - see the "9 extra cards" investigation in Part 12.
CREATE OR REPLACE FUNCTION set_return(
    p_group_id INTEGER,
    p_start_date DATE,
    p_end_date DATE
) RETURNS TABLE (
    n_variants INTEGER,
    total_start NUMERIC,
    total_end NUMERIC,
    pct_return NUMERIC
) AS $$
    WITH d1 AS (
        SELECT ps.product_id, ps.sub_type_name, ps.market_price AS price
        FROM price_snapshots ps
        JOIN products p ON p.product_id = ps.product_id
        WHERE p.group_id = p_group_id AND p.is_sealed = FALSE
          AND ps.snapshot_date = p_start_date AND ps.market_price IS NOT NULL
    ),
    d2 AS (
        SELECT ps.product_id, ps.sub_type_name, ps.market_price AS price
        FROM price_snapshots ps
        JOIN products p ON p.product_id = ps.product_id
        WHERE p.group_id = p_group_id AND p.is_sealed = FALSE
          AND ps.snapshot_date = p_end_date AND ps.market_price IS NOT NULL
    ),
    common AS (
        SELECT d1.product_id, d1.sub_type_name, d1.price AS start_price, d2.price AS end_price
        FROM d1 JOIN d2 USING (product_id, sub_type_name)
    )
    SELECT
        COUNT(*)::INTEGER,
        ROUND(SUM(start_price), 2),
        ROUND(SUM(end_price), 2),
        CASE WHEN SUM(start_price) > 0
             THEN ROUND(((SUM(end_price) - SUM(start_price)) / SUM(start_price)) * 100, 2)
             ELSE NULL END
    FROM common;
$$ LANGUAGE sql STABLE;

-- Current (or as-of) total value of a set: sum of every single card's
-- market_price on one day. Singles only, same hard rule as set_return.
CREATE OR REPLACE FUNCTION set_value(
    p_group_id INTEGER,
    p_as_of_date DATE DEFAULT CURRENT_DATE
) RETURNS TABLE (
    n_variants INTEGER,
    total_value NUMERIC
) AS $$
    SELECT COUNT(*)::INTEGER, ROUND(SUM(ps.market_price), 2)
    FROM price_snapshots ps
    JOIN products p ON p.product_id = ps.product_id
    WHERE p.group_id = p_group_id AND p.is_sealed = FALSE
      AND ps.snapshot_date = p_as_of_date AND ps.market_price IS NOT NULL;
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Lookup primitives (v2, added 2026-08-14)
--
-- The three functions below are the read path every future component needs
-- before it can ask anything interesting: find an item, see which variants
-- it actually has, then pull that variant's history. They're deliberately
-- "item", not "card" - sealed product is looked up exactly the same way,
-- the same reasoning that renamed card_return -> item_return.
--
-- Note the split: search returns products, NOT (product, variant) rows.
-- A product with Normal/Holofoil/Reverse Holofoil is ONE search hit with
-- variant_count = 3, not three hits. Collapsing variants into a single
-- "price" would violate the never-collapse-sub-types invariant, so search
-- reports how many exist and item_variants() gives their real prices.
-- ---------------------------------------------------------------------------

-- Pull one named field out of a product's extendedData blob.
--
-- tcgcsv stores card metadata as an array of {name, value} objects rather
-- than as columns, so every read of it is this same unwrap. Factored out
-- so callers don't hand-roll the jsonb_array_elements each time and quietly
-- disagree about it.
--
-- Note this is the same blob the is_sealed classifier keys on (presence of
-- a 'Number' field) - see CLAUDE.md. Verified 2026-08-14: 'Number' and
-- 'Rarity' are both populated for all 34,435 singles, Pokemon and One
-- Piece alike, so they're safe to rely on rather than treat as optional.
CREATE OR REPLACE FUNCTION extended_field(p_extended_data JSONB, p_field TEXT)
RETURNS TEXT AS $$
    SELECT ed->>'value'
    FROM jsonb_array_elements(p_extended_data) ed
    WHERE ed->>'name' = p_field
    LIMIT 1;
$$ LANGUAGE sql IMMUTABLE;

-- Reduce a card number to a comparable key, so "004", "4" and "004/102"
-- all match each other.
--
-- Two game-specific shapes have to be handled, confirmed against the real
-- data (2026-08-14, singles only):
--   Pokemon  24,765 "004/102"   the card number is BEFORE the slash
--            1,399  "XY121"     letters then digits, no separator
--            916    "196"       bare
--            532    "TG10/TG30" same slash rule, letters kept
--            111    "098a/122"  variant suffix letter, kept ("98a" != "98")
--   One Piece 6,274 "OP17-028"  the card number is AFTER the dash - the
--                               leading part is the set code, not the card
-- Getting the side wrong would silently match the set size or the set code
-- instead of the card, so the two are split on separately rather than by a
-- single generic rule.
--
-- Leading zeros are stripped from the start of each digit run ("004" -> "4",
-- "098a" -> "98a") but interior and trailing zeros are untouched - "040" and
-- "4" are genuinely different cards, only the padding is noise.
CREATE OR REPLACE FUNCTION normalize_card_number(p_raw TEXT)
RETURNS TEXT AS $$
    WITH cleaned AS (
        -- Tolerate how a person types it: "#004", "(11)", stray spaces.
        SELECT btrim(lower(btrim(COALESCE(p_raw, ''))), ' #()[].') AS v
    ),
    local_part AS (
        SELECT CASE
            WHEN v = '' THEN NULL
            -- "004/102" -> "004", "tg10/tg30" -> "tg10"
            WHEN v LIKE '%/%' THEN split_part(v, '/', 1)
            -- "op17-028" -> "028" (set code first, card number second)
            WHEN v ~ '^[a-z]+[0-9]*-[0-9]+$' THEN split_part(v, '-', 2)
            ELSE v
        END AS v FROM cleaned
    )
    -- The lookahead stops a bare "0" from being erased entirely.
    SELECT NULLIF(regexp_replace(v, '(^|[^0-9])0+(?=[0-9])', '\1', 'g'), '')
    FROM local_part;
$$ LANGUAGE sql IMMUTABLE;

-- The digits-only view of the same key, so typing "121" finds "XY121" and
-- "28" finds "OP17-028" without the caller having to know the set code.
-- Kept separate from normalize_card_number rather than replacing it, since
-- dropping the letters would also collapse "98a" onto "98".
CREATE OR REPLACE FUNCTION card_number_digits(p_raw TEXT)
RETURNS TEXT AS $$
    SELECT NULLIF(
        regexp_replace(
            regexp_replace(COALESCE(normalize_card_number(p_raw), ''), '[^0-9]', '', 'g'),
            '^0+(?=[0-9])', '', 'g'),
        '');
$$ LANGUAGE sql IMMUTABLE;

-- Name search across products, newest-relevance first. Case-insensitive
-- substring match, backed by the pg_trgm GIN index on products.name (see
-- schema.sql) - without that index this is a full seq scan on every
-- keystroke.
--
-- Ranking is deliberately simple and explainable rather than a similarity
-- score: exact name match, then prefix match, then any substring; ties
-- broken by name length so "Charizard" outranks "Charizard G" and
-- "Bug Catcher - 189/236 (#34 Charizard Stamped)". Card names are short
-- and highly repeated across sets (266 Pokemon products match
-- 'charizard'), so set_name/game are returned on every row - a bare name
-- list is genuinely ambiguous here, not just less pretty.
--
-- p_category_id filters to one game (NULL = all tracked games).
-- p_include_sealed = FALSE hides sealed product. It IS a parameter here,
-- unlike in set_return/set_value where sealed is hardcoded out - looking
-- something up is not a set-level aggregate, and "find me that booster
-- box" is a legitimate search.
--
-- card_number and rarity come from extendedData, NOT from the name, and
-- they are not decoration - they are frequently the only thing separating
-- two hits. The same set routinely reprints one card at several rarities:
-- SV03 Obsidian Flames has three "Charizard ex" (125/197 Double Rare,
-- 215/197 Ultra Rare, 223/197 Special Illustration Rare) that trade at
-- very different prices. TCGplayer does append the number to the name in
-- those cases, but only sometimes - just 5,276 of 34,435 singles have a
-- number in the name, so Base Set Charizard would come back with nothing
-- to identify it. extendedData carries Number and Rarity for 100% of
-- singles in both tracked games, so that's the source used here.
-- CREATE OR REPLACE cannot change a function's return type ("cannot change
-- return type of existing function"), so adding a column to the RETURNS
-- TABLE below makes a plain re-run of this file fail against any database
-- that already has the older signature. Dropping first keeps this file
-- re-runnable, which is the whole point of it - it's loaded on fresh boot
-- (where this is a no-op) and re-applied by hand after every edit. Signature
-- is spelled out because item_search is overload-free but Postgres still
-- needs the exact argument list to identify it.
DROP FUNCTION IF EXISTS item_search(TEXT, INTEGER, BOOLEAN, INTEGER, INTEGER);
DROP FUNCTION IF EXISTS item_search_core(TEXT, TEXT, INTEGER, BOOLEAN, INTEGER, INTEGER);

-- The search itself, with name and card number already separated. Callers
-- normally want item_search() below, which parses a single typed string
-- into these two parts; this is split out so that parsing and matching stay
-- separately testable, and so the wrapper can run it twice (see its
-- fallback) without duplicating the query.

-- Is this captured token a real variant descriptor, or just punctuation the
-- name happens to end with? Shared by both halves of variant_label() so a
-- parenthetical and a bracket are judged identically.
--
--   * must contain a letter    - rejects "(2)", "(147)"
--   * must not be a card code  - rejects "(H1)", "(XY72)", "(SWSH014)";
--     same shape item_search() uses to split a number off a typed query
--   * must be 3+ characters    - rejects "Unown [A]" .. "[Z]"
CREATE OR REPLACE FUNCTION is_variant_word(p_token TEXT)
RETURNS BOOLEAN AS $$
    SELECT p_token IS NOT NULL
       AND p_token ~ '[A-Za-z]'
       AND p_token !~ '^[A-Za-z]{0,4}[0-9]+[A-Za-z]?$'
       AND length(p_token) >= 3;
$$ LANGUAGE sql IMMUTABLE;

-- Human-readable variant qualifier from a product name, or NULL when the
-- product is an ordinary printing.
--
-- This exists because TCGplayer encodes the variant in the NAME, not in
-- extendedData - unlike Number and Rarity, which have their own fields.
-- "Blastoise - 13/95 (Cracked Ice Holo)" and plain "Blastoise" are
-- different products at different prices, and the parenthetical is the only
-- thing that says so.
--
-- Derived from the name alone, deliberately: that makes it correct for
-- every product today, independent of whether the image matcher ever finds
-- a picture for it (see DEVELOPMENT_HISTORY.md Part 21).
--
-- Three rules, each earning its place against real data:
--
--  1. A trailing "- YYYY (Person)" is a World Championship deck - the
--     parenthetical is the DECK PILOT, not a variant, so rendering it
--     as-is would put a stranger's name on the card. Verified 2026-08-18:
--     all 1,947 Pokemon products matching this shape are in World
--     Championship Decks and nothing else, so the year is safe to promote.
--  2. A parenthetical that is really a card number is not a variant.
--     "Alakazam (2)", "Crobat (147)" and "Latios EX (XY72)" all carry the
--     number in the name; the code shape (up to 4 letters then digits) is
--     the same one item_search() uses to split a typed number off a query.
--  3. A trailing [Staff] / [Bracket] token is a genuine variant of its own
--     ("... (Prerelease) [Staff]"), so it is kept and appended rather than
--     stripped with the rest of the punctuation. It faces the SAME validity
--     test as the parenthetical, which is not optional: Unown is catalogued
--     as "Unown [A]" ... "Unown [Z]", and without the length floor every
--     one of those 26 cards gets a one-letter "variant" label. The real
--     bracket vocabulary is words - Staff, Winner, Set of 4, 1st Place.
CREATE OR REPLACE FUNCTION variant_label(p_name TEXT)
RETURNS TEXT AS $$
DECLARE
    v_name  TEXT := btrim(COALESCE(p_name, ''));
    v_brack TEXT;
    v_paren TEXT;
    v_out   TEXT;
BEGIN
    IF v_name = '' THEN
        RETURN NULL;
    END IF;

    -- TCGplayer sometimes appends the card number AFTER the qualifier
    -- ("Professor's Research [Professor Magnolia] - 223"), which would hide
    -- the bracket from the trailing match below. Drop a trailing number
    -- first. Safe for the two shapes that must survive it: a World
    -- Championship name ends in ")" and "Blastoise - 13/95 (Cracked Ice
    -- Holo)" ends in ")", so neither matches.
    v_name := btrim(regexp_replace(v_name, '\s*-\s*[0-9A-Za-z]+(/[0-9A-Za-z]+)?\s*$', ''));

    -- Rule 3: lift a trailing [Staff]-style token before anything else, so
    -- the parenthetical behind it is still the last thing on the string.
    v_brack := btrim(COALESCE(substring(v_name FROM '\[([^\]]+)\]\s*$'), ''));
    v_name  := btrim(regexp_replace(v_name, '\s*\[[^\]]+\]\s*$', ''));
    IF NOT is_variant_word(v_brack) THEN
        v_brack := NULL;
    END IF;

    IF v_name ~ '- [0-9]{4} \([^)]+\)$' THEN
        -- Rule 1: World Championship deck.
        v_out := substring(v_name FROM '- ([0-9]{4}) \([^)]+\)$') || ' World Championship';
    ELSE
        v_paren := btrim(COALESCE(substring(v_name FROM '\(([^)]*)\)\s*$'), ''));
        IF is_variant_word(v_paren) THEN
            v_out := v_paren;
        END IF;
    END IF;

    IF v_brack IS NOT NULL THEN
        v_out := CASE WHEN v_out IS NULL THEN btrim(v_brack)
                      ELSE v_out || ' · ' || btrim(v_brack) END;
    END IF;

    RETURN v_out;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION item_search_core(
    p_name TEXT,
    p_number TEXT DEFAULT NULL,
    p_category_id INTEGER DEFAULT NULL,
    p_include_sealed BOOLEAN DEFAULT TRUE,
    p_limit INTEGER DEFAULT 25,
    p_offset INTEGER DEFAULT 0
) RETURNS TABLE (
    product_id INTEGER,
    name TEXT,
    card_number TEXT,
    rarity TEXT,
    variant_label TEXT,
    set_name TEXT,
    game TEXT,
    is_sealed BOOLEAN,
    variant_count INTEGER,
    latest_date DATE,
    url TEXT,
    image_url TEXT
) AS $$
    -- The LIMIT has to be applied BEFORE the per-product variant lookup,
    -- not after: as one CTE with the lateral join inline, Postgres runs
    -- the variant scan for every match (266 for 'charizard') and only then
    -- takes 25 - measured at 722ms vs 37ms for this shape.
    WITH hits AS (
        SELECT p.product_id, p.name, p.is_sealed, p.group_id, p.category_id,
               p.url, p.image_url, p.extended_data,
               CASE WHEN lower(p.name) = lower(p_name) THEN 0
                    WHEN lower(p.name) LIKE lower(p_name) || '%' THEN 1
                    ELSE 2 END AS rank
        FROM products p
        WHERE p.name ILIKE '%' || COALESCE(p_name, '') || '%'
          AND (p_category_id IS NULL OR p.category_id = p_category_id)
          AND (p_include_sealed OR p.is_sealed = FALSE)
          -- Number match runs only when one was supplied, and only against
          -- rows the (indexed) name filter already kept - so the JSON
          -- unwrap happens for a handful of rows, not the whole catalog.
          --
          -- Two ways to match. The full normalized key is the strict one
          -- ("xy67a" matches only XY67a). The digits-only key is the loose
          -- one, so "121" finds XY121 and "28" finds OP17-028 without
          -- having to type the set code - but it is allowed ONLY when the
          -- typed number has no letters in it. Otherwise typing "XY67a"
          -- would also match XY67, since both reduce to digits "67",
          -- throwing away the variant letter the user deliberately typed.
          AND (p_number IS NULL
               OR normalize_card_number(extended_field(p.extended_data, 'Number'))
                    = normalize_card_number(p_number)
               OR (p_number !~ '[A-Za-z]'
                   AND card_number_digits(extended_field(p.extended_data, 'Number'))
                         = card_number_digits(p_number)))
        -- product_id is the final tiebreaker purely for determinism, and it
        -- is load-bearing for paging: many products share an identical name
        -- across sets (there are dozens of cards named exactly "Charizard"),
        -- so rank/length/name alone leaves them tied and Postgres free to
        -- order them differently per call - which makes OFFSET silently
        -- duplicate or skip rows between pages.
        ORDER BY rank, length(p.name), p.name, p.product_id
        LIMIT LEAST(COALESCE(p_limit, 25), 200) OFFSET GREATEST(COALESCE(p_offset, 0), 0)
    )
    -- Extracted after the LIMIT, same reasoning as the variant lookup: no
    -- point unwrapping JSON for 266 matches to return 25.
    SELECT h.product_id, h.name,
           extended_field(h.extended_data, 'Number'),
           extended_field(h.extended_data, 'Rarity'),
           variant_label(h.name),
           g.name, c.name, h.is_sealed,
           v.variant_count, v.latest_date, h.url, h.image_url
    FROM hits h
    JOIN groups g ON g.group_id = h.group_id
    JOIN categories c ON c.category_id = h.category_id
    CROSS JOIN LATERAL (
        SELECT COUNT(DISTINCT ps.sub_type_name)::INTEGER AS variant_count,
               MAX(ps.snapshot_date) AS latest_date
        FROM price_snapshots ps WHERE ps.product_id = h.product_id
    ) v
    ORDER BY h.rank, length(h.name), h.name, h.product_id;
$$ LANGUAGE sql STABLE;

-- What callers actually use: one typed string, name and number figured out
-- from it. "Charizard 004", "Charizard 4", "Charizard#004", "Charizard #4"
-- and "Charizard 004/102" all find the same card; "Charizard" alone still
-- searches by name exactly as before.
--
-- How the split is decided:
--   * An explicit '#' always wins - everything left of it is the name,
--     everything right is the number. This is the escape hatch for a card
--     whose name genuinely ends in a number.
--   * Otherwise the LAST whitespace-separated token is treated as a number
--     only if it looks like one (contains a digit, and is otherwise just
--     letters/digits with an optional / or - separator). "Charizard ex"
--     keeps "ex" as part of the name; "VMAX", "GX" and "V" are likewise
--     safe, because none of them contain a digit.
--
-- **The fallback is the important part.** That last-token rule guesses, and
-- it can guess wrong - a card genuinely named "... 2" would be read as
-- name "..." plus number 2. So when the parsed interpretation returns
-- nothing, the whole original string is retried as a plain name. A wrong
-- guess therefore costs one extra query and is invisible to the user,
-- rather than showing an empty result for a card that does exist. The
-- second query only ever runs on a miss.
CREATE OR REPLACE FUNCTION item_search(
    p_query TEXT,
    p_category_id INTEGER DEFAULT NULL,
    p_include_sealed BOOLEAN DEFAULT TRUE,
    p_limit INTEGER DEFAULT 25,
    p_offset INTEGER DEFAULT 0
) RETURNS TABLE (
    product_id INTEGER,
    name TEXT,
    card_number TEXT,
    rarity TEXT,
    variant_label TEXT,
    set_name TEXT,
    game TEXT,
    is_sealed BOOLEAN,
    variant_count INTEGER,
    latest_date DATE,
    url TEXT,
    image_url TEXT
) AS $$
DECLARE
    v_query TEXT := btrim(COALESCE(p_query, ''));
    v_name  TEXT;
    v_num   TEXT;
    v_last  TEXT;
    v_hits  INTEGER;
BEGIN
    IF position('#' IN v_query) > 0 THEN
        v_name := btrim(split_part(v_query, '#', 1));
        v_num  := NULLIF(btrim(split_part(v_query, '#', 2)), '');
    ELSIF v_query ~ '\s' THEN
        v_last := regexp_replace(v_query, '^.*\s+', '');
        -- Digit required, so word-shaped suffixes (ex, GX, VMAX) stay name.
        IF v_last ~ '^[(\[]?[A-Za-z]{0,4}[0-9]+[A-Za-z]?([/-][A-Za-z0-9]+)?[)\]]?$' THEN
            v_num  := v_last;
            v_name := btrim(regexp_replace(v_query, '\s+\S+$', ''));
        END IF;
    END IF;

    IF v_num IS NOT NULL THEN
        RETURN QUERY SELECT * FROM item_search_core(
            COALESCE(v_name, ''), v_num, p_category_id,
            p_include_sealed, p_limit, p_offset);
        GET DIAGNOSTICS v_hits = ROW_COUNT;
        IF v_hits > 0 THEN
            RETURN;
        END IF;
    END IF;

    -- No number parsed, or the parse found nothing: plain name search.
    RETURN QUERY SELECT * FROM item_search_core(
        v_query, NULL, p_category_id, p_include_sealed, p_limit, p_offset);
END;
$$ LANGUAGE plpgsql STABLE;

-- Every variant of one product, with its current price and all-time high.
-- This is the bridge between search and item_return/item_all_time_high:
-- both of those require a sub_type_name, and a caller has no way to guess
-- whether a given card exists as Normal, Holofoil, Reverse Holofoil, or
-- some combination - it varies per product and even changes over time
-- (Altaria 83514's Normal variant only starts 2025-03-28 while its
-- Holofoil goes back to 2024-02-08, which is why first_date is returned
-- rather than assumed).
--
-- all_time_high uses the same market->mid->low fallback as
-- item_all_time_high() and cross-checks against it exactly; latest_market
-- is deliberately raw market_price with no fallback, so "no market price
-- recorded that day" reads as NULL instead of quietly substituting a
-- different, less reliable number.
CREATE OR REPLACE FUNCTION item_variants(
    p_product_id INTEGER
) RETURNS TABLE (
    sub_type_name TEXT,
    first_date DATE,
    latest_date DATE,
    latest_market NUMERIC,
    all_time_high NUMERIC
) AS $$
    WITH variants AS (
        SELECT ps.sub_type_name AS stn,
               MIN(ps.snapshot_date) AS first_date,
               MAX(ps.snapshot_date) AS latest_date,
               MAX(COALESCE(ps.market_price, ps.mid_price, ps.low_price)) AS ath
        FROM price_snapshots ps
        WHERE ps.product_id = p_product_id
        GROUP BY ps.sub_type_name
    )
    SELECT v.stn, v.first_date, v.latest_date, ps.market_price, v.ath
    FROM variants v
    JOIN price_snapshots ps
      ON ps.product_id = p_product_id
     AND ps.sub_type_name = v.stn
     AND ps.snapshot_date = v.latest_date
    ORDER BY v.stn;
$$ LANGUAGE sql STABLE;

-- Price time series for one product+variant, for charting.
--
-- high_price is deliberately NOT returned. Sellers price-park (list at
-- absurd values so nothing sells), so it's noise - CLAUDE.md's "store it,
-- ignore it" rule. Omitting it from the API entirely makes that rule
-- structural rather than a convention a future caller has to know about,
-- the same way set_return() hardcodes sealed product out instead of
-- offering a parameter.
--
-- trendable_price is the documented market->mid->low fallback (see
-- PriceSnapshot.trendablePrice) precomputed, so a chart doesn't have to
-- re-derive the fallback rule - and can't get it wrong.
--
-- NULL dates mean "no bound": both NULL gives the item's full history.
CREATE OR REPLACE FUNCTION item_history(
    p_product_id INTEGER,
    p_sub_type_name TEXT,
    p_start_date DATE DEFAULT NULL,
    p_end_date DATE DEFAULT NULL
) RETURNS TABLE (
    snapshot_date DATE,
    market_price NUMERIC,
    low_price NUMERIC,
    mid_price NUMERIC,
    direct_low_price NUMERIC,
    trendable_price NUMERIC
) AS $$
    SELECT ps.snapshot_date, ps.market_price, ps.low_price, ps.mid_price,
           ps.direct_low_price,
           COALESCE(ps.market_price, ps.mid_price, ps.low_price)
    FROM price_snapshots ps
    WHERE ps.product_id = p_product_id
      AND ps.sub_type_name = p_sub_type_name
      AND (p_start_date IS NULL OR ps.snapshot_date >= p_start_date)
      AND (p_end_date IS NULL OR ps.snapshot_date <= p_end_date)
    ORDER BY ps.snapshot_date;
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Movers (v3, added 2026-08-16)
-- ---------------------------------------------------------------------------

-- Biggest price moves over a window, for the "what moved this week" screen.
--
-- Rows are per (product, variant), NOT per product like item_search. A mover
-- IS a specific variant's move - Holofoil and Reverse Holofoil are different
-- markets that move independently, so collapsing them would be the sub-type
-- invariant violation, not the other way round.
--
-- **Averaged at both ends, not a two-point comparison.** This is the whole
-- reason the function looks more complicated than "price then vs price now."
-- Thinly-traded cards lurch on single sales: Charizard Star (Delta Species,
-- 84198) sat at $4,000 for weeks, printed $980 for exactly one day, snapped
-- back to $4,000, then settled near $990. Point-to-point that reads as -75%
-- one day and +308% the next, and a decliners board would be topped by that
-- kind of artifact every single day.
--
-- Measured on that exact card, so the effect is stated honestly rather than
-- assumed. As of the blip day the unsmoothed move is -75.50%; smoothed over
-- 3 days it is -25.17%. As of a week later, when the drop was real and
-- sustained, smoothing still reports -75.10%. So averaging does NOT erase a
-- one-day artifact - it damps it about 3x, which is enough to push it well
-- down a leaderboard - while leaving a genuine sustained move at full
-- magnitude. That asymmetry is the point. A bigger p_smoothing damps harder
-- at the cost of reacting more slowly.
--
-- Both windows must be FULLY covered (n = p_smoothing at each end).
-- A partial window would average fewer days and quietly weight them more.
--
-- p_min_price exists because percent change flatters cheap cards: without
-- it the top decliner for a week was Shuckle at $0.06 -> $0.01 (-83%), and
-- four of the top twelve were sub-$1 cards moving a few cents. Defaults to
-- $1, applied to the STARTING average.
--
-- market_price only, no mid/low fallback - same reasoning as item_return
-- (DEVELOPMENT_HISTORY.md Part 12): a fallback price on a two-anchor
-- comparison is where the -99% return bug came from.
--
-- Restricted to the basket present in both windows, like set_return, so
-- tcgcsv covering more or fewer products between the two dates can never
-- masquerade as a price move.
DROP FUNCTION IF EXISTS item_movers(INTEGER, INTEGER, INTEGER, BOOLEAN, NUMERIC, TEXT, INTEGER, INTEGER, DATE);
CREATE OR REPLACE FUNCTION item_movers(
    p_days INTEGER DEFAULT 7,
    p_category_id INTEGER DEFAULT NULL,
    p_group_id INTEGER DEFAULT NULL,
    p_include_sealed BOOLEAN DEFAULT FALSE,
    p_min_price NUMERIC DEFAULT 1,
    p_direction TEXT DEFAULT 'down',
    p_smoothing INTEGER DEFAULT 3,
    p_limit INTEGER DEFAULT 50,
    p_as_of DATE DEFAULT NULL
) RETURNS TABLE (
    product_id INTEGER,
    name TEXT,
    card_number TEXT,
    rarity TEXT,
    set_name TEXT,
    game TEXT,
    sub_type_name TEXT,
    is_sealed BOOLEAN,
    then_price NUMERIC,
    now_price NUMERIC,
    pct_change NUMERIC,
    url TEXT
) AS $$
DECLARE
    v_smooth      INTEGER := GREATEST(COALESCE(p_smoothing, 3), 1);
    v_end         DATE;
    v_recent_from DATE;
    v_prior_to    DATE;
    v_prior_from  DATE;
BEGIN
    -- Window bounds are resolved to plain DATE variables BEFORE the query
    -- rather than computed in a CTE. That is a 5x difference, not a style
    -- preference: with the bounds coming from a sub-select, the planner
    -- cannot fold them into constants, fails to use idx_snapshots_date
    -- properly, and aggregates far more of the 41M-row table than needed -
    -- measured spilling ~6GB to temp files. As scalars the same query is an
    -- ordinary indexed range scan over six days.
    v_end := COALESCE(p_as_of, (SELECT MAX(snapshot_date) FROM price_snapshots));
    v_recent_from := v_end - (v_smooth - 1);
    v_prior_to    := v_end - p_days;
    v_prior_from  := v_end - p_days - (v_smooth - 1);

    RETURN QUERY
    WITH recent AS (
        SELECT ps.product_id, ps.sub_type_name,
               AVG(ps.market_price) AS p, COUNT(*) AS n
        FROM price_snapshots ps
        WHERE ps.snapshot_date BETWEEN v_recent_from AND v_end
          AND ps.market_price IS NOT NULL
        GROUP BY 1, 2
    ),
    prior AS (
        SELECT ps.product_id, ps.sub_type_name,
               AVG(ps.market_price) AS p, COUNT(*) AS n
        FROM price_snapshots ps
        WHERE ps.snapshot_date BETWEEN v_prior_from AND v_prior_to
          AND ps.market_price IS NOT NULL
        GROUP BY 1, 2
    )
    SELECT
        pr.product_id,
        pr.name,
        extended_field(pr.extended_data, 'Number'),
        extended_field(pr.extended_data, 'Rarity'),
        g.name,
        c.name,
        prior.sub_type_name,
        pr.is_sealed,
        ROUND(prior.p, 2),
        ROUND(recent.p, 2),
        ROUND((recent.p - prior.p) / prior.p * 100, 2),
        pr.url
    FROM prior
    JOIN recent USING (product_id, sub_type_name)
    JOIN products pr ON pr.product_id = prior.product_id
    JOIN groups g ON g.group_id = pr.group_id
    JOIN categories c ON c.category_id = pr.category_id
    WHERE prior.n = v_smooth
      AND recent.n = v_smooth
      AND prior.p >= COALESCE(p_min_price, 0)
      AND prior.p > 0
      AND (p_category_id IS NULL OR pr.category_id = p_category_id)
      AND (p_group_id IS NULL OR pr.group_id = p_group_id)
      AND (p_include_sealed OR pr.is_sealed = FALSE)
    ORDER BY
        CASE WHEN p_direction = 'up' THEN (recent.p - prior.p) / prior.p END DESC NULLS LAST,
        CASE WHEN p_direction <> 'up' THEN (recent.p - prior.p) / prior.p END ASC NULLS LAST,
        pr.product_id
    LIMIT LEAST(COALESCE(p_limit, 50), 200);
END;
$$ LANGUAGE plpgsql STABLE;

-- Sets for a game, newest first, for the movers screen's set filter.
-- Only groups that actually have tracked products, so the dropdown can't
-- offer a set that would return nothing.
CREATE OR REPLACE FUNCTION game_sets(p_category_id INTEGER)
RETURNS TABLE (
    group_id INTEGER,
    name TEXT,
    published_on DATE,
    product_count INTEGER
) AS $$
    SELECT g.group_id, g.name, g.published_on::DATE, COUNT(p.product_id)::INTEGER
    FROM groups g
    JOIN products p ON p.group_id = g.group_id
    WHERE g.category_id = p_category_id
    GROUP BY g.group_id, g.name, g.published_on
    ORDER BY g.published_on DESC NULLS LAST, g.name;
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Set overview (added 2026-08-18)
--
-- One row per set: what it's worth today and how it moved over a window.
-- The per-set primitives set_value()/set_return() already answer this for
-- ONE set, but the screen needs every set of a game at once - 302 tracked
-- groups, so calling them per set is 604 round trips. This is those two
-- functions' definitions computed set-wide in a single pass; the semantics
-- are deliberately identical, and set_return()'s numbers are the reference
-- these must match.
--
-- Three things carried over from the primitives, all load-bearing:
--
--   * Singles only (is_sealed = FALSE), hardcoded, not a parameter. Same
--     hard rule as set_return() - see this file's header and CLAUDE.md.
--   * The return uses only rows priced on BOTH days (an inner join on
--     product_id + sub_type_name), so a set's composition changing inside
--     the window can't masquerade as a price move.
--   * pct_return is NULL, never 0, when the starting basket is <= 0.
--
-- Hence n_priced vs n_common, and total_value vs total_end: value is every
-- single priced on the end date, the return is only the common basket. They
-- differ whenever a card was priced today but not p_days ago, and the UI
-- says so rather than leaving a reader to notice two numbers that should
-- match and don't.
--
-- WHY THE CONCENTRATION COLUMNS EXIST (top_*). Measured 2026-08-18, not
-- theorized: among Pokemon sets with a >=2% 30-day move, the single largest
-- contributing row is routinely 50-87% of ALL movement in the set. Base Set
-- (Shadowless) read +59.96% on a basket of 202 - and $9,750 of the $10,718
-- move was one row, Charizard 1st Edition Holofoil, whose market_price
-- toggles between ~$250 and exactly $10,000 for weeks at a time while low,
-- mid and high sit parked at $100,000. That is a thin market flipping
-- regimes, not a 40x rally, and a two-point return lands on whichever
-- regime each chosen date happened to fall in.
--
-- Smoothing does NOT fix it and was tried first: the regimes run far longer
-- than a 3-day window, so item_movers()-style averaging returns $250 and
-- $10,000 unchanged. The measure that does help is disclosure - report the
-- biggest single contributor and its share of gross movement, so a set
-- carried by one oscillating row is visible instead of quietly trusted.
-- Same defect class as sealed product dominating set aggregates (Part 12
-- and 14); this one can't be fixed by excluding a category, because the
-- offending row is a legitimate single.
--
-- top_share is a share of GROSS movement (SUM of |delta|), not of the net.
-- Net goes through zero - a set whose gains and losses cancel has a
-- near-zero denominator and every ratio against it explodes to meaningless
-- four-digit percentages. Gross is always >= |net| and stays interpretable.
--
-- p_days defaults to 30 rather than movers' 7: a set of 200+ cards averages
-- out most of what a week shows, and the seven-day view was mostly noise
-- when checked.
CREATE OR REPLACE FUNCTION game_set_overview(
    p_category_id INTEGER DEFAULT NULL,
    p_days        INTEGER DEFAULT 30,
    p_as_of       DATE    DEFAULT NULL
) RETURNS TABLE (
    group_id        INTEGER,
    set_name        TEXT,
    game            TEXT,
    published_on    DATE,
    n_priced        INTEGER,
    total_value     NUMERIC,
    n_common        INTEGER,
    total_start     NUMERIC,
    total_end       NUMERIC,
    pct_return      NUMERIC,
    top_product_id  INTEGER,
    top_name        TEXT,
    top_sub_type    TEXT,
    top_delta       NUMERIC,
    top_share       NUMERIC,
    top_url         TEXT,
    start_date      DATE,
    end_date        DATE
) AS $$
DECLARE
    v_end   DATE;
    v_start DATE;
BEGIN
    -- Resolved to scalars before the query, not computed in a CTE. Same
    -- reason as item_movers(): from a sub-select the planner can't fold
    -- these into constants, misses idx_snapshots_date and aggregates far
    -- more of the 41M-row table than needed. As scalars it's a two-day
    -- indexed range scan.
    v_end   := COALESCE(p_as_of, (SELECT MAX(ps.snapshot_date) FROM price_snapshots ps));
    v_start := v_end - GREATEST(COALESCE(p_days, 30), 1);

    RETURN QUERY
    WITH now_rows AS (
        SELECT p.group_id AS gid, ps.product_id AS pid,
               ps.sub_type_name AS stn, ps.market_price AS price
        FROM price_snapshots ps
        JOIN products p ON p.product_id = ps.product_id
        WHERE ps.snapshot_date = v_end
          AND ps.market_price IS NOT NULL
          AND p.is_sealed = FALSE
          AND (p_category_id IS NULL OR p.category_id = p_category_id)
    ),
    then_rows AS (
        SELECT ps.product_id AS pid, ps.sub_type_name AS stn,
               ps.market_price AS price
        FROM price_snapshots ps
        JOIN products p ON p.product_id = ps.product_id
        WHERE ps.snapshot_date = v_start
          AND ps.market_price IS NOT NULL
          AND p.is_sealed = FALSE
          AND (p_category_id IS NULL OR p.category_id = p_category_id)
    ),
    -- set_value(): every single priced on the end date.
    val AS (
        SELECT nr.gid, COUNT(*)::INTEGER AS n_priced, SUM(nr.price) AS total_value
        FROM now_rows nr
        GROUP BY 1
    ),
    -- set_return()'s basket: priced on both days.
    moves AS (
        SELECT nr.gid, nr.pid, nr.stn,
               tr.price AS start_price, nr.price AS end_price,
               (nr.price - tr.price) AS delta
        FROM then_rows tr
        JOIN now_rows nr ON nr.pid = tr.pid AND nr.stn = tr.stn
    ),
    ret AS (
        SELECT m.gid, COUNT(*)::INTEGER AS n_common,
               SUM(m.start_price) AS total_start,
               SUM(m.end_price) AS total_end,
               SUM(ABS(m.delta)) AS gross
        FROM moves m
        GROUP BY 1
    ),
    -- Biggest single contributor per set, by absolute move. DISTINCT ON is
    -- the cheap form here: one ordered pass, no window function over every
    -- row of every set.
    top AS (
        SELECT DISTINCT ON (m.gid)
               m.gid, m.pid, m.stn, m.delta
        FROM moves m
        ORDER BY m.gid, ABS(m.delta) DESC, m.pid
    )
    SELECT
        g.group_id,
        g.name,
        c.name,
        g.published_on::DATE,
        val.n_priced,
        ROUND(val.total_value, 2),
        COALESCE(ret.n_common, 0),
        ROUND(ret.total_start, 2),
        ROUND(ret.total_end, 2),
        CASE WHEN ret.total_start > 0
             THEN ROUND((ret.total_end - ret.total_start) / ret.total_start * 100, 2)
             ELSE NULL END,
        top.pid,
        pr.name,
        top.stn,
        ROUND(top.delta, 2),
        CASE WHEN ret.gross > 0
             THEN ROUND(ABS(top.delta) / ret.gross * 100, 1)
             ELSE NULL END,
        pr.url,
        v_start,
        v_end
    FROM groups g
    JOIN categories c ON c.category_id = g.category_id
    -- Inner join on val: a set with nothing priced on the end date has no
    -- value and no return, so it is absent rather than a row of nulls.
    JOIN val ON val.gid = g.group_id
    LEFT JOIN ret ON ret.gid = g.group_id
    LEFT JOIN top ON top.gid = g.group_id
    LEFT JOIN products pr ON pr.product_id = top.pid
    WHERE (p_category_id IS NULL OR g.category_id = p_category_id)
    ORDER BY g.published_on DESC NULLS LAST, g.name;
END;
$$ LANGUAGE plpgsql STABLE;
