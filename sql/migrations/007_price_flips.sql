-- Flipping prices: cards whose market price keeps jumping between two
-- far-apart levels, and the preference that brings them back into trends.
--
-- Mirrors price_flips and user_settings.include_flipping in schema.sql.
--
-- What it looks like (2026-09-30, the last 180 days): Base Set (Shadowless)
-- Charizard 1st Edition Holofoil read either ~$250-490 or exactly $10,000,
-- holding each for days or weeks, and went back and forth 15 times; its only
-- listing is parked at $100,000, so the market price moves only on the odd
-- sale. Every switch moved its set's value by ~$9,700 and put it at the top
-- or bottom of Movers. One-day errors are already despiked on the Sets
-- screen; these hold for days, so nothing caught them.
--
-- 97 cards match the rule in refresh_price_flips(), 22 of them with a level
-- of $5 or more. The owner's call (2026-09-30): leave them out of trends by
-- default with a box to bring them back, say so on the card, and never touch
-- values. DEVELOPMENT_HISTORY.md Part 50.
--
-- price_flips is DERIVED, like card_first_priced: rebuilt from
-- price_snapshots by refresh_price_flips() after each day's pull, never a
-- source of truth and never written by hand.
CREATE TABLE IF NOT EXISTS price_flips (
    product_id       INTEGER NOT NULL,
    sub_type_name    TEXT    NOT NULL,
    level_low        NUMERIC(12,2) NOT NULL,
    level_high       NUMERIC(12,2) NOT NULL,
    reversals        INTEGER NOT NULL,
    computed_through DATE    NOT NULL,
    PRIMARY KEY (product_id, sub_type_name)
);

ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS include_flipping BOOLEAN NOT NULL DEFAULT FALSE;
