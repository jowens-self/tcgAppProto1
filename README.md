# CardTracker

A personal tool. Pulls daily TCG price data from the TCGCSV mirror into a
local Postgres database, append-only, so you build a price history you own.
Started Pokemon-only; which games are actually tracked is controlled by the
`tracked_categories` table, not hardcoded - see "Tracking another game"
below.

## What it deliberately does not do

- **No scraping.** Nothing in this project fetches from tcgplayer.com.
- **No automated login.** Your inventory comes from a CSV you export yourself.
- **Nothing public.** No server, no auth, no users.

Traffic goes only to `tcgcsv.com`, a third-party mirror on its own
infrastructure. Your seller account is never involved.

## Setup

```bash
docker compose up -d          # Postgres on 5432, schema loaded on first boot
mvn package                   # builds target/cardtracker-0.1.0.jar
java -jar target/cardtracker-0.1.0.jar
```

Override the defaults with `CARDTRACKER_DB_URL`, `CARDTRACKER_DB_USER`,
`CARDTRACKER_DB_PASS` if you want something other than local dev creds.

## Running a pull (on-demand, for now)

No scheduled job right now, by choice (see CLAUDE.md "On-demand pulls, not
a scheduled job") — there's no app front end yet consuming this data day to
day, so a background daemon just means Docker Desktop parked in memory for
no one. Instead, pull fresh data whenever you actually want to look at
something:

```bash
docker compose up -d                       # if Postgres isn't already running
mvn package                                # rebuild if source changed
java -jar target/cardtracker-0.1.0.jar
```

## Daily schedule (macOS) — built, tested, currently unloaded

A working LaunchAgent template is checked in at `com.cardtracker.daily.plist`
in the project root. It was installed and verified live on 2026-08-13, then
deliberately unloaded and removed from `~/Library/LaunchAgents` in favor of
the on-demand approach above. To reinstate it later:

```bash
cp com.cardtracker.daily.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.cardtracker.daily.plist
```

Runs at 03:15 local — the mirror refreshes around 20:00 UTC, so this always
picks up a complete day. Logs go to `/tmp/cardtracker.log` /
`/tmp/cardtracker.err`.

**Requires Docker Desktop to already be running** — it does not currently
auto-launch at login (only its privileged helper daemons do), so a reboot
needs Docker Desktop reopened by hand before the next 03:15 run, unless
"Start Docker Desktop when you log in" has been enabled in its own
Settings > General.

**Requires `mvn package` to have been run first** — the LaunchAgent invokes
the built jar directly (`java -jar target/cardtracker-0.1.0.jar`), not the
source. Rebuild after any code change or the scheduled runs will use stale
code.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.cardtracker.daily</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/java</string>
    <string>-jar</string>
    <string>/Users/YOU/cardtracker/target/cardtracker-0.1.0.jar</string>
  </array>
  <key>WorkingDirectory</key>
  <string>/Users/YOU/cardtracker</string>
  <key>StartCalendarInterval</key>
  <dict><key>Hour</key><integer>3</integer><key>Minute</key><integer>15</integer></dict>
  <key>StandardOutPath</key><string>/tmp/cardtracker.log</string>
  <key>StandardErrorPath</key><string>/tmp/cardtracker.err</string>
</dict>
</plist>
```

## Design decisions worth not undoing

**Prices key on (product_id, sub_type_name, snapshot_date).** Pokemon cards
exist as Normal, Holofoil and Reverse Holofoil of the same product, trading at
genuinely different prices. Collapse them and every percentage you compute is
an average of unrelated markets.

**`high_price` is stored but never computed on.** Sellers "price park" — list a
card at thousands of dollars so it doesn't sell, apparently to avoid re-picking
set/printing/condition when relisting. The top of the range is noise.

**Inserts are `ON CONFLICT DO NOTHING`.** Re-running a pull is safe, and a bad
run can't overwrite a good observation. History only accumulates.

**`product_id` is carried on every row**, so a link back to the card's page is
always constructible.

**Price source sits behind an interface.** TCGCSV is one person's free project
with no SLA and no per-condition pricing. If it disappears or you outgrow it,
you write one new class.

## Query functions

`sql/functions.sql` (loaded automatically alongside `schema.sql`) has four
parameterized SQL functions - the reusable analytical building blocks
worked out by hand in DEVELOPMENT_HISTORY.md Part 12, formalized in Part
14 so they don't need retyping per question. All verified to match the
hand-computed numbers exactly before being trusted.

`item_return`/`item_all_time_high` work on any individual product - card
or sealed, since the math is the same either way - and deliberately return
`name`/`is_sealed` alongside the numbers so the category is always visible
in the result, not something you have to already know.

```sql
-- Return for one item between two exact dates.
SELECT * FROM item_return(534919, 'Holofoil', '2024-02-08', '2026-08-13');
-- name              | is_sealed | start_price | end_price | pct_return
-- Mew ex - 232/091  | f         |      137.00 |   1013.78 |     639.99

-- All-time high for one item, market->mid->low fallback.
SELECT * FROM item_all_time_high(534919, 'Holofoil');
-- name              | is_sealed | all_time_high
-- Mew ex - 232/091  | f         |       1089.18

-- Portfolio-style set return: singles only, always - see CLAUDE.md
-- "A set's price/return is defined over its singles only".
SELECT * FROM set_return(23353, '2024-02-08', '2026-08-13');
-- n_variants | total_start | total_end | pct_return
--        325 |     1176.92 |   2790.15 |     137.07

-- Current (or as-of) total value of a set. Singles only, same rule.
SELECT * FROM set_value(23353);
-- n_variants | total_value
--        326 |     2792.05
```

Sealed product is still fully trackable - just as an individual item, not
folded into a set total: `SELECT * FROM item_return(528040, 'Normal',
'2024-02-08', '2026-08-13')` (the Paldean Fates Elite Trainer Box) works
the same as any single card.

## Example queries

Biggest 30-day movers, sealed product only:

```sql
WITH now_p AS (
  SELECT product_id, sub_type_name, market_price
    FROM price_snapshots WHERE snapshot_date = CURRENT_DATE),
then_p AS (
  SELECT product_id, sub_type_name, market_price
    FROM price_snapshots WHERE snapshot_date = CURRENT_DATE - 30)
SELECT p.name, n.sub_type_name, t.market_price AS then_price,
       n.market_price AS now_price,
       ROUND((n.market_price - t.market_price) / t.market_price * 100, 2) AS pct
  FROM now_p n
  JOIN then_p t USING (product_id, sub_type_name)
  JOIN products p USING (product_id)
 WHERE t.market_price > 0 AND p.is_sealed
 ORDER BY pct DESC
 LIMIT 25;
```

Did the pull actually run?

```sql
SELECT snapshot_date, status, request_count, rows_written, notes
  FROM ingest_runs ORDER BY started_at DESC LIMIT 7;
```

## Tracking another game

`tracked_categories` (see `sql/schema.sql`) controls which tcgcsv
categories `App`/`BackfillArchive` actually pull - seeded with just Pokemon
(3). Adding a game is a data change, not a code change:

```sql
INSERT INTO tracked_categories (category_id, name) VALUES (1, 'Magic');
```

The next `App` run (or a manual `BackfillArchive` call) picks it up
automatically - no rebuild needed. See tcgcsv's `/categories` endpoint or
CLAUDE.md "Multi-game support" for the full category list and important
caveats (Pokemon-specific quirks like code-card exclusion don't
automatically apply to a new game - verify before assuming).

## Backfill

The mirror publishes daily archives from 2024-02-08 onward at
`https://tcgcsv.com/archive/tcgplayer/prices-YYYY-MM-DD.ppmd.7z`. Each one
bundles every TCG category for that day - `BackfillArchive` processes every
category in `tracked_categories` from the same download, so tracking a
second game costs zero extra downloads for backfill. Needs `7z` on PATH
(`brew install p7zip`).

```bash
mvn package
java -cp target/cardtracker-0.1.0.jar com.cardtracker.BackfillArchive
# or a specific range:
java -cp target/cardtracker-0.1.0.jar com.cardtracker.BackfillArchive 2024-02-08 2024-12-31
```

Idempotent by (category, day) via `ingest_runs`
(`source = 'tcgcsv-archive-' || category_id`) — a day already recorded as
`ok` for a given category is skipped without downloading anything, so
re-running after an interruption, months later to catch up, or right after
adding a new tracked category, only processes what's missing. Ran clean on
2026-08-12: 916 days, ~37.7M rows, 0 failures (Pokemon only, at the time).

## Verified against live data

Field names in `TcgCsvPriceSource` matched the live mirror exactly when
checked on 2026-08-12 (`productId`, `subTypeName`, `marketPrice`, `lowPrice`,
`midPrice`, `highPrice`, `directLowPrice` — categories, groups, and products
endpoints too). Confirmed again at scale by the full backfill: ~97% of the
~37.7M backfilled rows have a populated `market_price`. The remaining ~3%
being null reflects the mirror's own data, not a mapping error - see the
null-safe parsing note in `TcgCsvPriceSource.decimal`.
