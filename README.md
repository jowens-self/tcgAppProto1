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
a scheduled job") — nothing reads this data unattended, so a background
daemon just means Docker Desktop parked in memory for no one. Missing a
day costs nothing: tcgcsv's archives persist back to 2024-02-08 and every
run backfills whatever's missing, so pull fresh data whenever you actually
want to look at something:

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

## Read API

A read-only JSON API over the query functions above, so a front end never
talks to Postgres (or to tcgcsv) directly. Read-only in the strict sense:
no endpoint here writes anything, and ingestion stays in
`App`/`BackfillArchive`.

```bash
docker compose up -d
mvn package
java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer
# http://localhost:7070  (override with CARDTRACKER_API_PORT)
```

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | Liveness check |
| `GET /api/games` | Tracked games, for a filter dropdown |
| `GET /api/items/search?q=` | Card / product search |
| `GET /api/items/{id}` | One product's identity |
| `GET /api/items/{id}/variants` | Its variants, with current price + all-time high |
| `GET /api/items/{id}/history?subType=` | Price time series for one variant |

Search takes `q` (required), plus optional `game` (category id),
`sealed=false` to hide sealed product, `limit` (default 25, capped at 200)
and `offset`.

**`q` accepts a name, or a name plus a card number.** All of these find the
same card:

```
Charizard 004      Charizard 4        Charizard#004
Charizard #4       Charizard 004/102
```

Leading zeros never matter on either side — `4` matches `004/102`, `04/82`
and `4/100`. Interior and trailing zeros do matter, since `040` and `4` are
different cards. A number is only parsed out when the last word actually
looks like one, so `Charizard ex`, `Charizard VMAX` and `Charizard GX`
still search purely by name. Use `#` to force the split on a card whose
name genuinely ends in a number.

Set codes are optional: `Shanks 120` and `Shanks OP01-120` both find
`OP01-120`, and `Jirachi 67` finds `XY67`. But a variant letter you type is
respected — `Jirachi XY67a` returns only `XY67a`, never `XY67`.

If the parsed reading finds nothing, the whole string is retried as a plain
name, so a wrong guess is invisible rather than an empty result
(`Nemona - Pikachu 42` is a real card whose number is `180/198`).

```bash
curl "localhost:7070/api/items/search?q=charizard&sealed=false&limit=3"
# [{"productId":42382,"name":"Charizard","cardNumber":"004/102",
#   "rarity":"Holo Rare","setName":"Base Set","game":"Pokemon",
#   "isSealed":false,"variantCount":1,"latestDate":"2026-08-14",
#   "url":"...","imageUrl":"..."}, ...]
```

**`cardNumber` and `rarity` are identity, not decoration.** A set routinely
reprints the same card at several rarities, and those copies are different
markets. All four of these are "Charizard ex" in SV03: Obsidian Flames:

| # | Rarity | Market |
|---|---|---|
| 125/197 | Double Rare | $5.65 |
| 215/197 | Ultra Rare | $22.35 |
| 223/197 | Special Illustration Rare | $108.58 |
| 228/197 | Hyper Rare | $37.37 |

Both fields come from `extendedData`, not from the name — TCGplayer only
sometimes appends the number to the name (5,276 of 34,435 singles), so
relying on the name would leave Base Set Charizard with nothing to identify
it. Null for sealed product, which has neither.

**Search returns products, not variants** — one row per product with a
`variantCount`, never one row per (product, sub-type). A card existing as
Normal / Holofoil / Reverse Holofoil is one hit, because there is no
single honest "price" to put on it (see "Design decisions worth not
undoing" above). Ask `/variants` for the real per-variant numbers:

```bash
curl "localhost:7070/api/items/83514/variants"
# [{"subTypeName":"Holofoil","firstDate":"2024-02-08","latestDate":"2026-08-14",
#   "latestMarket":64.72,"allTimeHigh":68.29}, ...]
```

`subType` is required on `/history` and has no default — picking one for
the caller would mean guessing which market they meant. `/variants` is
how you find the valid values.

```bash
curl "localhost:7070/api/items/534919/history?subType=Holofoil&from=2026-08-11"
# [{"snapshotDate":"2026-08-11","marketPrice":1013.78,"lowPrice":829.34,
#   "midPrice":1030.0,"directLowPrice":829.34,"trendablePrice":1013.78}, ...]
```

`high_price` is deliberately absent from every API response — see the
price-parking note above. `trendablePrice` is the documented
market→mid→low fallback, precomputed so a chart can't apply it wrong.

CORS is scoped to `http://localhost:4200` (the Angular dev server). The
API binds locally and is meant to stay there; if it ever moves, revisit
both that rule and the connection-per-request in `ApiServer.withRepo`.

## Front end (`web/`)

Angular 22 app over the read API. Card / product search: find an item, see
its variants and their current prices, chart one variant's history.

```bash
docker compose up -d                                              # Postgres
java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer  # API :7070
cd web && npm install && npx ng serve                             # UI :4200
```

All three are needed — the UI talks only to the API, and the API is the
only thing that talks to Postgres.

Structure, all standalone components with signals:

| File | Role |
|---|---|
| `api.models.ts` | TypeScript mirrors of the Java records |
| `api.ts` | The only file that knows a server exists |
| `search.ts/.html/.css` | The screen: query, filters, results, detail |
| `price-chart.ts` | Hand-rolled SVG line chart, no charting library |

Layout is two columns — results left, detail right and sticky, so the
chart stays in view while you scroll the list. The results list scrolls
within its own 70vh box rather than growing the page. Below 800px the
columns stack.

The chart plots `trendablePrice` (the fallback the server already applied)
and drops days with no price rather than plotting them as zero — a missing
observation isn't a price of $0.

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
