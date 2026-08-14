-- CardTracker schema (v1)
--
-- Design rules baked in here:
--   1. price_snapshots is APPEND-ONLY. We never UPDATE a price row. The whole
--      point of this project is the history; overwriting is the one mistake
--      that can't be undone.
--   2. A price is keyed by (product_id, sub_type_name, snapshot_date), NOT by
--      product_id alone. Pokemon cards exist as Normal / Holofoil / Reverse
--      Holofoil of the same product and they trade at different prices.
--   3. product_id survives everywhere, so we can always build a link back to
--      the card's page on TCGplayer.

CREATE TABLE IF NOT EXISTS categories (
    category_id   INTEGER PRIMARY KEY,
    name          TEXT NOT NULL,
    modified_on   TIMESTAMPTZ
);

-- Which tcgcsv categories App/BackfillArchive actually pull. Data, not
-- code, on purpose: adding a card game is "INSERT a row," not a code
-- change + rebuild + redeploy. Seeded with Pokemon (3) only, so nothing
-- changes in behavior until another row is added deliberately.
CREATE TABLE IF NOT EXISTS tracked_categories (
    category_id INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tracked_categories (category_id, name)
VALUES (3, 'Pokemon')
ON CONFLICT (category_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS groups (
    group_id        INTEGER PRIMARY KEY,
    category_id     INTEGER NOT NULL REFERENCES categories(category_id),
    name            TEXT NOT NULL,
    abbreviation    TEXT,
    is_supplemental BOOLEAN,
    published_on    TIMESTAMPTZ,
    modified_on     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_groups_category ON groups(category_id);

CREATE TABLE IF NOT EXISTS products (
    product_id    INTEGER PRIMARY KEY,
    group_id      INTEGER NOT NULL REFERENCES groups(group_id),
    category_id   INTEGER NOT NULL REFERENCES categories(category_id),
    name          TEXT NOT NULL,
    clean_name    TEXT,
    image_url     TEXT,
    -- The canonical TCGplayer product URL. Kept as a column rather than
    -- derived at render time so a link never depends on us guessing a
    -- URL format that may change.
    url           TEXT,
    -- Sealed boxes/packs vs singles. Flagged at ingest, not reverse-engineered
    -- from the name later.
    is_sealed     BOOLEAN NOT NULL DEFAULT FALSE,
    extended_data JSONB,
    modified_on   TIMESTAMPTZ,
    first_seen_on DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE INDEX IF NOT EXISTS idx_products_group ON products(group_id);

-- Name search (item_search() in functions.sql) is a leading-wildcard ILIKE,
-- which no btree index can serve - it was a full seq scan of every product
-- on every keystroke. Trigram GIN index takes 'charizard' across ~38.5k
-- products from ~30ms to ~1.3ms, and unlike the seq scan it doesn't
-- degrade as more games are added to tracked_categories.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);

-- The important one.
CREATE TABLE IF NOT EXISTS price_snapshots (
    product_id       INTEGER NOT NULL,
    sub_type_name    TEXT    NOT NULL,
    snapshot_date    DATE    NOT NULL,
    market_price     NUMERIC(12,2),
    low_price        NUMERIC(12,2),
    mid_price        NUMERIC(12,2),
    -- Stored for completeness but DO NOT compute on this. Sellers "price park"
    -- (list at absurd values so nothing sells), which makes high_price noise.
    high_price       NUMERIC(12,2),
    direct_low_price NUMERIC(12,2),
    ingested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, sub_type_name, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_snapshots_date ON price_snapshots(snapshot_date);
CREATE INDEX IF NOT EXISTS idx_snapshots_product ON price_snapshots(product_id, sub_type_name, snapshot_date DESC);

-- Your own holdings, loaded from a Seller Portal CSV export.
-- Columns are deliberately minimal until we see a real export file.
CREATE TABLE IF NOT EXISTS inventory_snapshots (
    snapshot_date    DATE    NOT NULL,
    product_id       INTEGER,
    sku_id           BIGINT,
    sub_type_name    TEXT,
    condition        TEXT,
    quantity         INTEGER NOT NULL,
    your_price       NUMERIC(12,2),
    source_file      TEXT,
    ingested_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_inventory_date ON inventory_snapshots(snapshot_date);

-- Products deliberately excluded from ingestion and all calculations - e.g.
-- digital code-card redemptions, which behave nothing like a physical card
-- and skew set-level return calculations (see DEVELOPMENT_HISTORY.md).
-- Kept as its own table, independent of `products`, because BackfillArchive
-- only ever sees a bare product_id from tcgcsv's archives - never a name -
-- so it needs somewhere to look up "is this excluded" that still works
-- after the product's row is removed from `products` entirely.
--
-- category_id is recorded (not just implied by joining to `products`, which
-- may already be gone by the time this is read) specifically so an
-- exclusion reason discovered for one game is never mistaken for a
-- universal rule about trading cards in general - code cards, for example,
-- are a Pokemon quirk, not something to assume any other game also has.
CREATE TABLE IF NOT EXISTS excluded_products (
    product_id    INTEGER PRIMARY KEY,
    category_id   INTEGER NOT NULL,
    name          TEXT NOT NULL,
    reason        TEXT NOT NULL,
    excluded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Operational log. Lets you answer "did the pull run, and did it run clean?"
-- without reading application logs.
CREATE TABLE IF NOT EXISTS ingest_runs (
    id             BIGSERIAL PRIMARY KEY,
    source         TEXT NOT NULL,
    snapshot_date  DATE,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    status         TEXT NOT NULL DEFAULT 'running',
    request_count  INTEGER NOT NULL DEFAULT 0,
    rows_written   INTEGER NOT NULL DEFAULT 0,
    notes          TEXT
);

-- hasSuccessfulRun() filters on exactly these three columns, and
-- BackfillArchive calls it once per day in its range - up to ~900 times in
-- one gap-catchup call. Without this, every one of those calls is a full
-- table scan (verified via EXPLAIN before adding this - see
-- DEVELOPMENT_HISTORY.md).
CREATE INDEX IF NOT EXISTS idx_ingest_runs_source_date_status
    ON ingest_runs(source, snapshot_date, status);
