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
