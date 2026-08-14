# CardTracker

## tcgcsv.com usage constraints — do not violate

This app's only external dependency is `tcgcsv.com`, a free, no-SLA mirror
run by one person. Verified against [tcgcsv.com/docs](https://tcgcsv.com/docs)
and [tcgcsv.com/faq](https://tcgcsv.com/faq) on 2026-08-12:

- **Hard ban threshold: >10,000 requests in a 24h period "may be banned."**
  A full sync should need at most 10,000 requests. Enforced in code as a
  circuit breaker in `Throttle.MAX_REQUESTS_PER_RUN` (9000) — do not raise
  this without re-reading the docs.
- **Pacing: >=100ms between requests** is the documented minimum (the FAQ's
  own example code uses 250ms). Exceeding a reasonable req/sec rate gets the
  IP throttled for 10 minutes. `Throttle.politeDefault()` uses 750ms —
  deliberately well inside the minimum. Don't tighten this without a reason.
- **Custom User-Agent required** on every request, format `App/X.Y.Z`.
  Generic or missing User-Agents may be blocked outright. Current value
  lives in `TcgCsvPriceSource.USER_AGENT` — bump the version number when
  request behavior changes.
- **Data refreshes once daily.** There's a `last-updated.txt` you can check
  before pulling; if the timestamp hasn't advanced since your last
  successful run, skip the sync. Not currently implemented — a future
  improvement, not required for ban-safety (normal usage is a few hundred
  requests/day, far under the cap).
- **No browser requests** — tcgcsv.com has a restrictive CORS policy and is
  backend-only by design, which matches how this app calls it.

Any change to request volume, retry logic, or concurrency in
`TcgCsvPriceSource` or `Throttle` should be checked against these numbers
before merging.

## Design constraints (from README — keep in sync, don't duplicate details)

See [README.md](README.md) "What it deliberately does not do" and "Design
decisions worth not undoing" for: no scraping tcgplayer.com directly, no
automated login, nothing public, variant-aware pricing key, append-only
inserts, and the `PriceSource` interface boundary. Those are the load-bearing
constraints for this project — respect them when making changes.


# CardTracker — project context

A personal tool for a TCGplayer seller. Pulls daily Pokemon price data from a
third-party mirror into local Postgres, append-only, to build a price history
the owner controls. Joins against their own inventory to answer: what's up,
what's down, what's dead stock, what's it worth this week vs last month.

Not a product. No users, no auth, no server, nothing public.

**Goal: one `price_snapshots` row per day for every card the owner has
included, with no gaps.** Completeness over time is the point — a missing
day is a defect, not a rounding error. `ingest_runs` exists so a gap is
visible rather than silently swallowed; a failed or skipped day should be
noticed and backfilled, not shrugged off.

## Hard constraints — do not violate, do not "improve"

These are risk decisions, not style preferences. The owner's TCGplayer seller
account is real income; the project is speculative. Protecting the account
outranks every feature.

- **Never scrape tcgplayer.com.** Their terms prohibit crawling without
  permission. No HTML fetching, no headless browser, no "public search API"
  calls. Not for one card, not as a fallback, not behind a flag.
- **Never automate a login.** Inventory arrives as a CSV the owner exports by
  hand from the Seller Portal and drops in `inbox/`. That manual step is the
  design, not a gap to close.
- **All outbound traffic goes to tcgcsv.com only.** If a task seems to require
  hitting TCGplayer directly, stop and ask rather than finding a way.

If asked to add scraping, say what it risks before writing it.

## Data invariants

- **Prices key on `(product_id, sub_type_name, snapshot_date)`.** Pokemon cards
  exist as Normal / Holofoil / Reverse Holofoil of the same product and trade
  at different prices. Never collapse sub-types or aggregate across them.
- **`price_snapshots` is append-only.** Inserts use `ON CONFLICT DO NOTHING`.
  Never change this to `DO UPDATE`. Re-running a pull must be safe; a bad run
  must never overwrite a good observation. The history is the whole asset.
- **Never compute on `high_price`.** Sellers "price park" — listing at absurd
  values so nothing sells. Store it, ignore it. Trend on `market_price`, with
  `mid` then `low` as fallback (see `PriceSnapshot.trendablePrice`).
- **Our own "all-time high" is computed on demand, not tracked in a table.**
  Decided 2026-08-12: `MAX(...) GROUP BY product_id, sub_type_name` against
  `price_snapshots`, run whenever it's needed - see `item_all_time_high()`
  in `sql/functions.sql`. Deliberately not an incrementally-updated table:
  this is unrelated to tcgcsv's noisy `high_price` column above. A
  running-high table only compares against new incoming rows, so a later
  backfill that turns up an older, higher price would leave it silently
  wrong. Recomputing from `price_snapshots` is always correct after a
  backfill, no reconciliation code needed. Revisit only if `MAX()` over the
  full table is ever too slow to run live.
  **Correction, 2026-08-13:** originally written as plain `MAX(market_price)`,
  which quietly contradicted the general trending rule right above this one
  ("Trend on `market_price`, with `mid` then `low` as fallback"). Reconciled
  to `MAX(COALESCE(market_price, mid_price, low_price))` when
  `item_all_time_high()` was built - a MAX() over the whole history doesn't
  have the anchor-bias risk that point-to-point comparisons do (see Part 12's
  return-calculation bug), so there's no reason to withhold the fallback
  here the way `item_return`/`set_return` deliberately do for their
  start/end anchors.
- **A set's price/return is defined over its singles only - never sealed
  product.** Decided 2026-08-13, after finding sealed product (booster
  boxes, ETBs, tins, cases) was dominating set-level numbers once
  `is_sealed` became real - one ~$8,441 booster bundle display case alone
  was a third of one set's "total value" (see Part 12 and Part 14). This is
  not a caller-adjustable default - `set_return()`/`set_value()` in
  `sql/functions.sql` hardcode `is_sealed = FALSE` internally, no parameter
  for it, the same way code-card exclusion is baked into ingestion rather
  than left as a query-time filter. Sealed product is still fully
  trackable as an individual item via `item_return()`/`item_all_time_high()`
  (those work on any product_id regardless of category) - it just never
  contributes to a set-level aggregate. "The set" and "sealed product
  belonging to that set" are two categorically different things, tracked
  at two different granularities (individual items vs. sets as a whole).
- **`product_id` rides on every row through every transform.** Outbound links
  to TCGplayer are a core feature; an aggregate that loses the ID is a bug.
- **Percent change returns null on a zero or negative starting price.** Do not
  "fix" this to return 0 or infinity. Every return-calculation query so far
  has been ad-hoc SQL (`CASE WHEN start_price > 0 THEN ... ELSE NULL END`,
  see DEVELOPMENT_HISTORY.md Part 12) - there's no Java implementation of
  this yet (`Change.percent` existed briefly with zero call sites and was
  removed 2026-08-13; reintroduce it if/when percent-change logic actually
  moves into application code instead of interactive SQL).

## Mirror etiquette

TCGCSV is one person's free project with no SLA. Their stated rules: one pull
per 24 hours, under 10,000 requests per full sync, descriptive User-Agent
(generic ones get blocked), ~100ms between requests. `Throttle` enforces this
structurally. If a sync starts needing more requests, the sync logic is doing
redundant work — fix that, don't raise the ceiling.

Prices refresh around 20:00 UTC. The daily job runs 03:15 local.

## Architecture intent

`PriceSource` and `InventorySource` exist so the data sources are swappable.
TCGCSV has no per-condition pricing and no uptime guarantee; a paid provider
may replace it later. Keep provider specifics inside the implementations —
nothing above them should know the mirror exists.

Future: Angular front end, then cloud. Both talk to our own API, never to the
mirror directly (it has a restrictive CORS policy and asks that data be
ingested server-side).

## Multi-game support (decided 2026-08-13/14)

**Not Pokemon-only anymore, by design.** This started as a Pokemon-specific
tool (`TcgCsvUrls.POKEMON` hardcoded everywhere), but tcgcsv covers ~90
categories (Magic, Yu-Gi-Oh!, Lorcana, One Piece, Digimon, Flesh & Blood,
Star Wars Unlimited, and many more - checked live before committing to
this). Generalized so tracking another game is a data change, not a code
change: `tracked_categories` (schema.sql) lists which category_ids
`App`/`BackfillArchive` actually pull. Add a row, the next run picks it up.
Seeded with only Pokemon (3) so nothing changes in behavior until another
row is added deliberately.

**Do not assume Pokemon-specific quirks generalize to other games.**
Code-card exclusion (see "Excluded products" below) is scoped to
`categoryId == TcgCsvUrls.POKEMON` specifically in `App.pullCategory` - it
is a Pokemon quirk (physical tins bundling a Pokemon TCG Live redemption
code), not a general trading-card-industry thing, and nothing currently
confirms any other game has an equivalent. When a new game turns up its own
quirk, add it the same way - a new condition alongside the Pokemon one, own
`reason` string in `excluded_products` - rather than broadening the
existing check to cover categories it was never verified against.
`is_sealed`'s classifier (presence of a `Number` field in `extendedData`)
spot-checked fine against a Magic single (Darksteel Angel) before this
generalization, but that's one card in one set, not the same level of
verification Pokemon got (1999 Base Set through 2026 sets) - worth a
proper check the first time a new game's data actually gets pulled in
volume, not just assumed to hold everywhere tcgcsv's schema looks similar.

**One Piece Card Game (category 68) added 2026-08-14 - the first real
second game, not just architecture.** Verified before adding: `is_sealed`
held cleanly across two separate real samples (sealed products correctly
empty on `extendedData`, singles correctly carrying `Number`) - three
games for three now, not an assumption. No code-card-style quirk found in
the samples checked, though that's a spot-check, not exhaustive. Full
history: 918 days backfilled (2024-02-08 - the hard floor of tcgcsv's
archive system for any game, regardless of when that game actually
launched - through present), 0 failures, then a live pull to populate the
catalog (archives never carry names) - 7,261 products, 633 sealed / 6,628
singles, matching the same is_sealed split ratio Pokemon showed. Proved
the generalization is real, not just designed: `item_return()` - written
and tested against Pokemon only, untouched since - worked on a One Piece
product_id with zero code changes (Monkey.D.Luffy (001) Foil: $173.45 ->
$961.19, +454.16%).

**Mechanics of how this actually works, for future reference:**
- `ingest_runs.source` is category-scoped: `"tcgcsv-" + categoryId` for
  live pulls, `BackfillArchive.sourceFor(categoryId)` (`"tcgcsv-archive-"
  + categoryId`) for archive backfills. Each tracked category gets fully
  independent idempotency, gap-catchup, and failure tracking - one game
  failing never blocks or masks another's success. Existing history was
  migrated from the old bare `tcgcsv`/`tcgcsv-archive` names to
  `tcgcsv-3`/`tcgcsv-archive-3` when this was built (a plain `UPDATE`, not
  a delete - `ingest_runs` is operational metadata, not the append-only
  price history, so relabeling it is fine).
- `BackfillArchive.run()` downloads one archive per day and processes every
  tracked category's subfolder from that same download, since archives
  already bundle every TCG category regardless - adding a second tracked
  game costs zero extra downloads for backfill, just more local parsing.
- `App.main()` now always calls the gap catch-up unconditionally (no more
  per-category "is yesterday covered" pre-check) - `BackfillArchive.run()`
  is already cheap when nothing's missing, so a separate check was pure
  duplication once it had to reason about multiple categories anyway. Each
  tracked category is then pulled and isolated in its own try/catch inside
  `main`'s loop, so a failure in one category doesn't stop the others.
- `Throttle` stays a single shared instance across every category in one
  run - the 9000-request circuit breaker is a whole-process safety limit,
  not a per-category allowance, so sharing it is required for correctness,
  not just convenience. `App` tracks each category's own request count as
  a delta against the shared cumulative total, so `ingest_runs.request_count`
  stays accurate per category despite the shared counter.
- **Watch the 10,000/24h ban threshold as more categories get added.** One
  category of Pokemon's size costs ~436 requests/day. That's nowhere near
  the ceiling alone, but it's not unlimited headroom either - re-check the
  math in "tcgcsv.com usage constraints" above before tracking many large
  games at once.

## Environment

Java 21, Maven, Postgres 16 via `docker compose up -d`. macOS. Schema loads
from `sql/schema.sql` on first container boot.

**Maven installed 2026-08-13** via `brew install maven` (no sudo needed,
unlike the Docker Desktop cask). Before this, the project had no persisted
build output anywhere - every run that session was `javac` against jars
sitting in a scratch temp dir. `mvn package` now produces a real
`target/cardtracker-0.1.0.jar` (shaded, `App` as main class) that the
LaunchAgent below depends on. Rebuild with `mvn package` after any source
change - the LaunchAgent runs the jar, not the source.

**Daily automation was tried, then deliberately paused 2026-08-13.**
`~/Library/LaunchAgents/com.cardtracker.daily.plist` was loaded and verified
working (confirmed via `launchctl list`, and `/usr/bin/java` resolves
correctly even under `env -i`, so it works fine under launchd's bare
environment). Then unloaded and the file removed from `~/Library/LaunchAgents`
on purpose - see "On-demand pulls, not a scheduled job" below. The plist
template still lives at `com.cardtracker.daily.plist` in the project root,
ready to reinstate (`cp` it back into `~/Library/LaunchAgents` and
`launchctl load` it) once there's an actual reason to run unattended.

**On-demand pulls, not a scheduled job (decided 2026-08-13).** There's no
app front end yet to justify a background daemon - the only thing that
reads this data today is a person manually querying Postgres or running the
jar. Running a background scheduled job for that is standing infrastructure
(Docker Desktop kept resident, ~3.5GB+ RAM parked continuously on a 16GB
Mac) in service of a use case that doesn't exist yet. Instead: run
`docker compose up -d` and `java -jar target/cardtracker-0.1.0.jar` by hand
whenever you actually want to look at something. Revisit real scheduling
once there's a front end (see "Architecture intent" - Angular, then cloud)
where "always up to date without a manual step" actually matters. Until
then, a day with no manual pull is just a day with no new row - not a bug,
since nothing depends on unattended completeness yet.

**`App` skips the fetch if today (UTC) is already pulled** - added
2026-08-13, right after the on-demand decision above, because without it
"run it whenever I check something" meant a full ~5.5 minute, 436-request
re-fetch on every single check, even if today's data was already sitting in
Postgres from an hour ago. `App.main` now calls
`repo.hasSuccessfulRun(source.name(), snapshotDate)` first (the same method
`BackfillArchive` already used for its own day-level idempotency) and
returns immediately if a successful run for today already exists, instead
of hitting tcgcsv again. Verified: second run same day completed in under a
second instead of ~5.5 minutes. A failed run does not count - `App` will
retry a day that previously errored out, same as `BackfillArchive` does.

**`App` also catches up missing prior days before pulling today** - added
2026-08-13, same session. On-demand pulls mean real gaps can accumulate (go
5 days without opening the app, and without this, only day 6 would ever get
filled - see the "if I skip 5 days" discussion). `BackfillArchive`'s main
loop was extracted into a reusable `BackfillArchive.run(start, end, repo)`
(main() is now a thin CLI wrapper around it; `SOURCE` and `EARLIEST_ARCHIVE`
made public). `App.main`, after the today-already-done check above, checks
whether *yesterday* has a successful run under either source
(`tcgcsv` live or `tcgcsv-archive`) - if not, it calls
`BackfillArchive.run(EARLIEST_ARCHIVE, yesterday, repo)` before doing
today's live pull. That call is cheap even though the range looks huge:
`run()` already skips any day with a prior successful run via one lookup
per day, so on the normal steady-state day (no gap) this whole block is
skipped entirely, and even when it does run, only the genuinely missing
days trigger a real download.
**Correction, 2026-08-13:** that lookup was claimed "indexed" here from the
start, but no index actually existed until the full-codebase review in
DEVELOPMENT_HISTORY.md Part 13 caught it - `EXPLAIN` showed a full
`Seq Scan`. Fixed with `idx_ingest_runs_source_date_status` on
`ingest_runs(source, snapshot_date, status)`; the claim above is accurate
now, it just wasn't when originally written.
**Verified end-to-end 2026-08-13**, not just by code review. Spun up a second,
fully isolated Postgres container (`cardtracker-db-test`, port 5433, same
`sql/schema.sql`, no shared volume with the real `cardtracker-db`), seeded
`ingest_runs` with 914 fake "ok" rows for source `tcgcsv-archive` covering
2024-02-08 through 2026-08-09, and deliberately left 2026-08-10/11/12 (a
real 3-day gap) plus today unseeded. Ran the real jar against it via
`CARDTRACKER_DB_URL` pointed at the scratch container - no code changes, no
mocking. Result: `App` printed "No data for 2026-08-12 yet - catching up..."
correctly identified all 914 seeded days as covered (skipped instantly),
pulled real data from tcgcsv's actual archives for exactly the 3 missing
days ("2026-08-10: 45163 rows", "...11: 45161", "...12: 45156", "914
already had, 3 backfilled, 0 failed"), then proceeded to a normal today's
live pull (45,156 rows, 436 requests - matches the real DB's own numbers
for the same day). Scratch container stopped and removed after; confirmed
the real `cardtracker-db` was untouched throughout (row counts unchanged).
This is now a proven feature, not just a reasoned-through one.
Known edge case: if tcgcsv hasn't published yesterday's archive yet (lag
time unverified), that one day's catch-up attempt fails and gets logged as
`status='failed'` in `ingest_runs`, but does not stop `App` - it proceeds to
today's live pull regardless, and will retry the failed day next time it's
still "yesterday or earlier" and uncovered.

Known gap if scheduling is reinstated later: Docker Desktop doesn't
auto-launch at login (only its privileged helper daemons do), so a reboot
would silently break an unattended nightly pull until Docker Desktop is
reopened by hand. Enable "Start Docker Desktop when you log in" in Docker
Desktop's own Settings > General before relying on any scheduled job again.

## Known unknowns — verify before trusting

1. ~~Field names in `TcgCsvPriceSource` are unverified.~~ **Verified 2026-08-12**
   via direct curl against `tcgcsv.com/tcgplayer/3/groups` and
   `.../3/604/prices` (Base Set) — `productId`, `subTypeName`, `marketPrice`,
   `lowPrice`, `midPrice`, `highPrice`, `directLowPrice` all present and
   correctly named, no live pull needed to confirm this. Live data also
   showed a real price-parked row (`marketPrice` ~70, `highPrice` 9999),
   confirming the high_price decision above is warranted.
2. **The Seller Portal CSV's columns have never been seen.** `InventorySource`
   is an interface with no implementation on purpose. Do not guess the columns;
   ask for a sample export first. Whether it contains a SKU ID or only a
   product ID determines whether per-condition joins are possible at all.
3. ~~Product identity across time is unproven.~~ **Checked 2026-08-13** by
   mapping every `product_id -> group_id` from the raw 2024-02-08 archive
   (24,980 products) and diffing against the current `products` table
   (32,547 products, from the live pull). 24,891 product_ids overlap; 37
   (0.15%) show a different `group_id` now than in 2024. Inspected all 37 by
   name: every one is a promo/code card, basic energy card, or special-holo
   variant that TCGplayer re-filed into a different catalog bucket (e.g. into
   "SVE: Scarlet & Violet Energies" or "Miscellaneous Cards & Products") -
   the card name is identical before and after in every case, only the group
   changed. No product_id was found reassigned to an unrelated card.
   `price_snapshots` keys on `product_id` alone (no `group_id`), so this
   reclassification churn doesn't touch price history at all. Long-range
   trends and all-time-high queries are safe to trust.
   Caveat: this only tests 2024-02-08 vs now (the earliest archive available)
   and only within Pokemon. Good enough to stop treating this as an open risk.

## Excluded products

**Code cards are excluded entirely, everywhere - decided 2026-08-13.** While
building the first real analytical queries (% return over time, per card and
per set), digital "Code Card" products (separate TCGplayer listings for the
online redemption code packed inside sealed product - see DEVELOPMENT_HISTORY.md
for the full investigation) turned out to skew set-level return calculations
badly: they crater toward $0 once redeemed, dragging the low end of any
aggregate way down, and they're not a real physical card anyone would want
to search for or trend anyway.

Excluded means excluded from ingestion, not just filtered at query time -
they're never written to `products` or `price_snapshots` going forward, and
the 1,253 that had already been backfilled (810,451 price rows) were deleted
outright. Classification is by name prefix ("Code Card...", case-insensitive),
checked live in `App` against each day's real product names.

A standalone `excluded_products` table (`product_id`, `category_id`, `name`,
`reason`, `excluded_at`) tracks what's been excluded and why, **independent
of** `products` - this matters because `BackfillArchive` only ever sees a
bare `product_id` from tcgcsv's archives, never a name (archives never
carry product metadata, only prices), so once an excluded product's row is
gone from `products`, archive backfill would have no way left to know to
skip it. `App` writes to `excluded_products` as a side effect of its live,
name-based classification (`upsertExcludedProduct`, `ON CONFLICT DO NOTHING`);
`BackfillArchive.run()` reads the accumulated set once per call
(`excludedProductIds()`, global across categories - a product_id already
belongs to exactly one category, so filtering by category there would be
redundant) and filters by ID before inserting. This also means a code card
from a set that hasn't had a live pull yet can't be excluded during backfill
until `App` has seen it once - acceptable since backfill only ever targets
recent past days in the on-demand model, and those days get caught up by
`App` itself (see the gap catch-up logic above).
`category_id` was added when multi-game support was built (see "Multi-game
support" above) specifically so an exclusion reason discovered for one game
is never mistaken for a universal rule - code cards are a Pokemon thing
until proven otherwise for something else.

Verified before deleting anything: filter logic tested live (via jshell)
against Paldean Fates' real API response - 295 products, 13 correctly
identified as code cards, 375 price rows correctly reduced to 362. After
cleanup, re-ran the set-level aggregate for Paldean Fates with no filter
clause at all (since the rows were simply gone) and got an exact match
against the query-time-filtered version: 361 variants, mean +103.26%,
median +50.06%, range -66.67% to +952.10%.

If another category of product turns out to need the same treatment later
(sealed product once `is_sealed` is real, something else entirely), reuse
this same table and mechanism rather than inventing a new one - `reason` is
already a free-text column for exactly that.

## Sealed product (`is_sealed`) is now real, not a placeholder

**Implemented 2026-08-13**, immediately after the code-card work above.
Set-level aggregates were still badly skewed even after excluding code
cards, because sealed product (booster boxes, ETBs, tins, cases, bundles)
was still mixed in with real singles - a single $8,441 booster bundle
display case was a third of one set's entire "total value." Unlike code
cards, sealed product isn't something to exclude outright - it's a real,
legitimate thing to track, just a different category that needs to be
filterable, which is exactly what `products.is_sealed` was always meant
for (see its schema comment) but had never been implemented - every row
was hardcoded `FALSE`.

**Classifier: presence of a `Number` field in `extendedData`.** A real
single card always carries its card number as a structured field; sealed
product never does - it only ever has a `CardText` marketing blurb, if
anything. This is data-driven, not a name-pattern guess like the code-card
check. Verified before trusting it: checked Paldean Fates in full (37
sealed / 245 singles, zero misclassifications either direction on
inspection), checked 1999 Base Set specifically to make sure the field
name is stable across the oldest and newest data in the archive (booster
boxes/theme decks/starter sets correctly `f`, singles including an
error-print variant and Trainer cards correctly `t`), then a 20-item
random sample across a wide range of sets after the retroactive fix -
19/20 singles correctly `false`, the one sealed item (a Sword & Shield
theme deck) correctly `true`.

Computed once in `TcgCsvPriceSource.products()` (where the raw
`extendedData` JSON is already being read) and carried on `Product.isSealed`
from construction onward - not reverse-engineered from the name later, and
not computed in SQL, so the same value is available for both product
metadata and set-level query filtering without re-deriving it. `is_sealed`
is now in `PriceRepository`'s `ON CONFLICT DO UPDATE` clause too, same as
every other real field on `products` - it wasn't before, since there was
no point updating a hardcoded constant.

**Existing catalog corrected retroactively**, same pattern as the code-card
cleanup: `UPDATE products SET is_sealed = NOT (extended_data @> '[{"name":
"Number"}]'::jsonb)` across the whole table (31,294 products at the time) -
27,807 came back singles, 3,487 sealed.

**Impact, concretely (Paldean Fates, singles only vs. previously mixed):**
current set value dropped from $25,100.93 to $2,792.05; set return dropped
from +369.53% to +137.07%. Sealed product wasn't minor noise in these
numbers, it was the dominant factor - any set-level query going forward
should filter on `is_sealed` explicitly rather than assume "the group" means
"the singles."

## Backlog

- ~~Verify field mappings against a live response~~ done 2026-08-12
- ~~Backfill from the 2024-onward archives~~ done 2026-08-12 - `BackfillArchive`,
  916 days, ~37.7M rows, 0 failures. Re-runnable to catch future gaps.
- ~~Wire `products()` into the daily `App` run~~ done 2026-08-12 - `App` now
  upserts category/groups/products every run, not just prices. Proven live:
  217 groups, 436 requests, 32,547 products, 1,973 new price rows.
- Inventory CSV ingest, once a real export exists
- Inventory + price join views
- ~~Open: local-vs-UTC date mismatch~~ resolved 2026-08-12 - decided UTC.
  `App`, `BackfillArchive`, and `ProductPullTest` all compute "today" via
  `LocalDate.now(ZoneOffset.UTC)`, not host-local. Matches the archive
  backfill's own day boundary and the container's default clock, so
  `snapshot_date` means the same thing across the whole table's history, and
  `CURRENT_DATE` in raw SQL now always agrees with it. If a new place ever
  needs "today," use `ZoneOffset.UTC` explicitly - bare `LocalDate.now()` is
  the bug this fixed.
  **One artifact from before the fix, deliberately left alone:** the
  2026-08-12 19:40 CDT live-pull run (45,156 rows) was stamped under the old
  host-local convention. Under the new rule that same moment would have been
  2026-08-13 (UTC had already rolled over). Not corrected - `price_snapshots`
  is append-only and that row was valid under the convention in effect when
  it was written. It's a one-time, already-happened quirk, not an ongoing
  bug; nothing later depends on it being "fixed."

