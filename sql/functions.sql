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
               CASE WHEN lower(p.name) = lower(p_query) THEN 0
                    WHEN lower(p.name) LIKE lower(p_query) || '%' THEN 1
                    ELSE 2 END AS rank
        FROM products p
        WHERE p.name ILIKE '%' || p_query || '%'
          AND (p_category_id IS NULL OR p.category_id = p_category_id)
          AND (p_include_sealed OR p.is_sealed = FALSE)
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
