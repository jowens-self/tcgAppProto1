-- CardTracker query functions (v1)
--
-- Formalizes the analytical building blocks worked out by hand in
-- DEVELOPMENT_HISTORY.md Part 12, as real, reusable, parameterized SQL
-- rather than hand-typed queries repeated per question.
--
-- Hard rule baked in here, not left as caller-adjustable: a set's price or
-- return is defined ONLY over its singles (is_sealed = FALSE). Sealed
-- product is a fully separate category - it's trackable individually via
-- card_return()/card_all_time_high() same as any product, but it never
-- contributes to a set-level aggregate. See CLAUDE.md.

-- Return for one specific product+variant between two exact dates. Empty
-- result (no row) if either date has no recorded price for that item -
-- deliberately not "nearest available date," so a caller never silently
-- gets a different day than the one they asked for.
CREATE OR REPLACE FUNCTION card_return(
    p_product_id INTEGER,
    p_sub_type_name TEXT,
    p_start_date DATE,
    p_end_date DATE
) RETURNS TABLE (
    start_price NUMERIC,
    end_price NUMERIC,
    pct_return NUMERIC
) AS $$
    SELECT
        s.market_price,
        e.market_price,
        CASE WHEN s.market_price > 0
             THEN ROUND(((e.market_price - s.market_price) / s.market_price) * 100, 2)
             ELSE NULL END
    FROM
        (SELECT market_price FROM price_snapshots
          WHERE product_id = p_product_id AND sub_type_name = p_sub_type_name
            AND snapshot_date = p_start_date AND market_price IS NOT NULL) s,
        (SELECT market_price FROM price_snapshots
          WHERE product_id = p_product_id AND sub_type_name = p_sub_type_name
            AND snapshot_date = p_end_date AND market_price IS NOT NULL) e;
$$ LANGUAGE sql STABLE;

-- All-time high for one product+variant. Uses the market->mid->low
-- fallback (see PriceSnapshot.trendablePrice / CLAUDE.md "Data invariants")
-- since this is a MAX() over the whole history, not a two-point comparison -
-- the anchor-bias risk from Part 12 doesn't apply here the same way.
CREATE OR REPLACE FUNCTION card_all_time_high(
    p_product_id INTEGER,
    p_sub_type_name TEXT
) RETURNS NUMERIC AS $$
    SELECT MAX(COALESCE(market_price, mid_price, low_price))
    FROM price_snapshots
    WHERE product_id = p_product_id AND sub_type_name = p_sub_type_name;
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
