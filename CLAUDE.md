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
  Decided 2026-08-12: `MAX(market_price) ... GROUP BY product_id, sub_type_name`
  against `price_snapshots`, run whenever it's needed — see README "Example
  queries". Deliberately not an incrementally-updated table: this is unrelated
  to tcgcsv's noisy `high_price` column above. A running-high table only
  compares against new incoming rows, so a later backfill (2024-onward
  archives, still on the backlog) that turns up an older, higher price would
  leave it silently wrong. Recomputing from `price_snapshots` is always
  correct after a backfill, no reconciliation code needed. Revisit only if
  `MAX()` over the full table is ever too slow to run live.
- **`product_id` rides on every row through every transform.** Outbound links
  to TCGplayer are a core feature; an aggregate that loses the ID is a bug.
- **Percent change returns null on a zero or negative starting price.** Do not
  "fix" this to return 0 or infinity — see `Change.percent`.

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
`run()` already skips any day with a prior successful run via one indexed
lookup, so on the normal steady-state day (no gap) this whole block is
skipped entirely, and even when it does run, only the genuinely missing
days trigger a real download.
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

