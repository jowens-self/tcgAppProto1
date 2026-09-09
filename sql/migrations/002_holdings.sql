-- Accounts, step 2: make inventory_snapshots a real, per-user, append-only log.
--
-- Mirrors the inventory_snapshots block in schema.sql.
--
-- Append-only, for the same reason price_snapshots is: the history IS the
-- product. Editing a quantity does not UPDATE a row, it writes a new one, and
-- "what do I hold now" means "the newest row for this card". That costs one
-- DISTINCT ON (see current_holdings() in functions.sql) and buys the ability to
-- ask what a collection was worth on any past date and have the answer be true,
-- rather than today's holdings priced at June's prices.
--
-- Deleting a card is a row with quantity 0, not a DELETE. A tombstone keeps the
-- log honest: "sold on the 4th" is information, and a vanished row is not.
--
-- The table is empty (0 rows), so every ALTER here is free. That stops being
-- true the moment anything is entered, which is the argument for doing it now.

-- Surrogate key. A holding has no natural one: the same card in the same
-- condition legitimately appears many times, once per correction.
ALTER TABLE inventory_snapshots ADD COLUMN IF NOT EXISTS id BIGSERIAL;
ALTER TABLE inventory_snapshots DROP CONSTRAINT IF EXISTS inventory_snapshots_pkey;
ALTER TABLE inventory_snapshots ADD PRIMARY KEY (id);

-- DEFAULT 1 is what lets every query, endpoint and view be written against
-- user_id today, before any login exists, and keep working untouched after.
-- The local owner is user_id 1; see 001_users.sql.
ALTER TABLE inventory_snapshots
    ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1
        REFERENCES users(user_id) ON DELETE CASCADE;

-- What you paid, which is NOT your_price. your_price is the Seller Portal's
-- column: what you are asking. cost_basis is what it cost you. Confusing the
-- two silently inverts every gain/loss figure in the app, so they are separate
-- columns rather than one field with a comment.
ALTER TABLE inventory_snapshots ADD COLUMN IF NOT EXISTS cost_basis NUMERIC(12,2);

-- 0 is meaningful (sold out), negative never is.
ALTER TABLE inventory_snapshots DROP CONSTRAINT IF EXISTS inventory_quantity_nonneg;
ALTER TABLE inventory_snapshots ADD CONSTRAINT inventory_quantity_nonneg
    CHECK (quantity >= 0);

-- Ordered exactly as current_holdings() reads it, so resolving "newest row per
-- card" is an index scan rather than a sort of the whole log.
CREATE INDEX IF NOT EXISTS idx_inventory_current
    ON inventory_snapshots (user_id, product_id, sub_type_name, condition,
                            snapshot_date DESC, ingested_at DESC);
