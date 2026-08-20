# CardTracker

A TCG price-history app, built by and initially for a TCGplayer/eBay
seller but **intended to be shared** - see below. Pulls daily price data
from a third-party mirror into Postgres, append-only, to build a price
history it controls. Answers: what's up, what's down, what's dead stock,
what's it worth this week vs last month.
Started Pokemon-only; which games are actually tracked is controlled by the
`tracked_categories` table, not hardcoded — see "Multi-game support" below.

**This is intended to ship.** Stated by the owner 2026-08-16: the app is
meant to be shared, not just a personal tool, and features should be "universal
enough to warrant shipping." It runs locally today because that is how far
it has got, not because it is meant to stay there. Design for other people
using it.

Several things written before that was clear now read as too small and will
need revisiting: the read-only API, connection-per-request,
localhost-only CORS, and the "personal tool" framing generally. None are
wrong today; all are wrong for a shipped app.

---

## Where the project actually is (updated 2026-08-19)

Built and working: daily price ingest for Pokemon (3) and One Piece (68)
with archive backfill to 2024-02-08 and zero gaps; the SQL function layer
in `sql/functions.sql`; a read-only Javalin API on :7070; an Angular 22
front end in `web/` with three screens (Card/Product Search, Movers,
Sets); and locally-cached Pokemon card images.

Behaviour changed 2026-08-18/19, worth knowing before reading the code:
smoothing is a **median**, never a mean (Part 24 addendum); the Sets screen
corrects one-day price errors and says so per row (Part 24); variant labels
appear only when not already in the card's name (Part 25); Movers and Sets
**open on the largest tracked game, not "All games"** - picked from
`productCount`, never a hardcoded category id (Part 25/f409cc8); and the
image matcher was fixed and re-run, taking Pokemon singles image coverage
from 60.6% to **94.1%** (16,841 -> 26,166 images; Part 26). Newest:
`card_image_provenance()` decides whose artwork a card actually shows by
asking which other products claim the same catalog card - 91% of the
6,820 qualifier-stripped rows resolve structurally; the owner's
vocabulary calls in `image_qualifier_class` settle the rest, 155 still
open (Part 27 + addendum). **That now reaches the UI** - the search detail
view bands borrowed artwork, and only borrowed artwork (Part 29).

**Check state before assuming anything** - `./status.sh` reports what's
running, image-import progress and data freshness in one shot. The backlog
at the bottom of this file goes stale faster than anything else here; two
shipped features were still listed as to-do a few hours after shipping.

Running it, all three needed together:

```
docker compose up -d                                                  # Postgres
java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer    # API :7070
cd web && npx ng serve                                                 # UI :4200
```

`java -jar target/cardtracker-0.1.0.jar` pulls prices (idempotent per day,
catches up gaps automatically). `com.cardtracker.images.ImportCardImages`
fetches card images - resumable, run it from the project root.

**Use `ImportCardImages --dry-run` before changing any matching rule.** It
matches and reports without downloading, writing, or recording a single
`no_match`. That matters because a `no_match` is never re-queried, so a
wrong rule writes a permanent lie - and the dry run has already caught one
(it flagged `Aerodactyl (1)` as a printing variant when `(1)` is a card
number). `--group <id>` scopes a real run to one set, which is how a rule
change should be validated before it touches 10,000 rows.

**Traps that have actually bitten, all of them more than theory:**

- **Never `mvn package` while ANY process is running from the jar.** It
  overwrites in place and Java loads classes lazily, so a long job dies
  partway through. **That includes the API server, which is easy to
  forget** - it happened 2026-08-19: the import was protected, the running
  `ApiServer` was not, and it kept serving until it needed a class it had
  not yet loaded, then threw `ClassNotFoundException` and 500'd the Sets
  screen. The fix is a restart, not a rebuild. Check first with
  `pgrep -fl 'java .*cardtracker'`.
- **`CREATE OR REPLACE FUNCTION` does not replace across signatures.**
  Adding a parameter creates a second OVERLOAD and leaves the old one
  behind; a call fitting both then fails as ambiguous rather than picking
  one. This bit `game_set_overview()` on 2026-08-19. Any function whose
  signature changes needs an explicit `DROP FUNCTION IF EXISTS
  name(old,arg,types);` above its CREATE - `item_movers()` and
  `game_set_overview()` both carry one. Inspect with
  `SELECT oid::regprocedure FROM pg_proc WHERE proname='...';`
- **Run tools from the project root.** The image directory default is
  relative, so a launch from elsewhere used to split the writer from the
  reader silently - the importer downloading into a fresh empty tree while
  the API served 404s from the real one. Both now resolve through
  `ImageDir.resolve()`, which **refuses to start** outside the project root
  (detected by `pom.xml` + `sql/schema.sql`) and names the fix in the error.
  An explicit `CARDTRACKER_IMAGE_DIR` is honoured unchecked, so an absolute
  path still works from anywhere.
- **`.gitignore` patterns without a leading slash match at any depth.** A
  bare `images/` silently excluded the Java package
  `src/main/java/com/cardtracker/images/`, and the importer sat
  uncommitted for a day. Anchor directory ignores: `/images/`.
- **Don't pipe a long-running Java process through `head`.** It ignores
  the resulting SIGPIPE and keeps running with output discarded, which
  looks exactly like a hang.
- **pokemontcg.io is flaky, in two different ways.** Expect 5-11 of 174
  sets to fail per attempt, and deep pagination is broken past ~12k rows.
  The importer retries and caches a complete catalog under
  `images/.catalog-cache` (7-day TTL); delete that file to force a refresh.
  Separately, **image downloads can fail in sustained bursts** - observed
  2026-08-19, an 11-minute stretch of zero successes and ~2 failures a
  minute, which then recovered on its own. With 6 retries at up to 60s
  each, a single image can occupy minutes, so **a silent log is not
  necessarily a hang**. Check progress in the DB
  (`SELECT status, COUNT(*) FROM card_images GROUP BY status`) before
  killing anything; failures are recorded as `failed`, which a later run
  retries.
- **The 61 remaining `failed` image rows are an upstream gap, not a
  retryable blip - do not keep re-running the importer for them.** Checked
  2026-08-20: a full retry pass re-checked 22 of the 61 and every one failed
  again, having already failed the day before. A transient burst would not
  reproduce across days, and would not *cluster by set* - these do: 51 of 61
  are McDonald's promo collections (`mcd14` 13, `mcd15` 14, `mcd17` 12,
  `mcd18` 12), plus 7 `hsp` and 1 `ecard2`. pokemontcg.io lists the cards
  (a `source_id` matched) but does not serve the images. Retrying costs ~6
  requests each against a free community API for no expected gain, which is
  the opposite of the etiquette this project owes them. The 61 are worth
  0.2% coverage; leave them unless the upstream gap is known to have been
  filled.

---

## Before you cite a rule here

Not every line in this file came from the owner. Some are design decisions
Claude made and recorded as description, and one of those - "Not a product.
No users, no auth, no server, nothing public" - was later quoted back at the
owner as though they had forbidden a hosted version. They never said it, and
it was used to argue against their own feature idea.

So: if something here appears to block what the owner is asking for, raise it
rather than treating it as settled. Sections that say a rule came from the
owner mean it; the rest is revisable.

## tcgcsv.com usage constraints — do not violate

Checked against tcgcsv's own docs/FAQ on 2026-08-12. Their published
limits, not our policy.

This app's only external dependency right now is `tcgcsv.com`, a free, no-SLA mirror
run by one person (Keeping an eye out for sourcing past what this provides is needed). Verified against [tcgcsv.com/docs](https://tcgcsv.com/docs)
and [tcgcsv.com/faq](https://tcgcsv.com/faq) on 2026-08-12:

- **Hard ban threshold: >10,000 requests in a 24h period "may be banned."**
  A full sync should need at most 10,000 requests. Enforced in code as a
  circuit breaker in `Throttle.MAX_REQUESTS_PER_RUN` (9000) — do not raise
  this without re-reading the docs. If a sync starts needing more requests,
  the sync logic is doing redundant work — fix that, don't raise the ceiling.
- **Pacing: >=100ms between requests** is the documented minimum (the FAQ's
  own example code uses 250ms). Exceeding a reasonable req/sec rate gets the
  IP throttled for 10 minutes. `Throttle.politeDefault()` uses 750ms —
  deliberately well inside the minimum. Don't tighten this without a reason.
- **Custom User-Agent required** on every request, format `App/X.Y.Z`.
  Generic or missing User-Agents may be blocked outright. Current value
  lives in `TcgCsvPriceSource.USER_AGENT` — bump the version number when
  request behavior changes.
- **Data refreshes once daily, around 20:00 UTC.** There's a
  `last-updated.txt` you can check before pulling; if the timestamp hasn't
  advanced since your last successful run, skip the sync. **Implemented
  2026-08-20** as `PriceSource.dataAsOf()` - and it turned out to be better
  than a skip: the timestamp says which DAY the live endpoints are serving,
  so the pull is dated from it rather than being abandoned. A premature run
  now re-writes a day already held, which `ON CONFLICT DO NOTHING` makes a
  free no-op. It is **a correctness fix, not just politeness** - this was
  filed as "a future improvement, not required for ban-safety" until
  2026-08-20, when a pull that ran before the daily refresh wrote **51,287
  rows under `snapshot_date` 2026-08-20 that are 100% identical to
  2026-08-19** - every product, every sub-type, same price. A phantom day.
  Verified across the whole history: exactly ONE such pair exists, so this is
  a one-off from an early pull, not a systemic corruption. Effects: a 1-day
  window reports every card at 0.00% (honestly - the stored data really is
  unchanged), and a duplicated day gets an extra vote in the 3-day medians
  Movers uses. Detect it with a per-date `COUNT(*)`/`SUM(market_price)`
  signature compared against the previous day. Note `price_snapshots` is
  append-only, so **whether to delete the phantom row is the owner's call**,
  not a cleanup to perform.
- **No browser requests** — tcgcsv.com has a restrictive CORS policy and is
  backend-only by design, which matches how this app calls it.

Any change to request volume, retry logic, or concurrency in
`TcgCsvPriceSource` or `Throttle` should be checked against these numbers
before merging.

## Hard constraints — do not violate, do not "improve"

Every rule in this section came from the owner directly, most of them
reaffirmed forcefully. These are the ones that protect real income.

These are risk decisions, not style preferences. The owner sells on
**both TCGplayer and eBay**, and both are real income; the project is
speculative. Protecting those accounts outranks every feature.

**The general rule, which governs every case below:** this project talks
to `tcgcsv.com` and nothing else. Any other outbound host - a marketplace,
a CDN, an image API, anything - requires the owner's explicit permission
first, asked for before any code is written or any request is made. Do not
reason from "this specific request seems harmless" to "therefore it's
allowed." The owner's livelihood is on the other side of that judgment
call, and it is not one to make on their behalf.

- **Never contact eBay.** Added 2026-08-15 at the owner's explicit
  instruction: they sell there as well. No API calls, no page fetches, no
  image hotlinking, no scraping, no automation - and not "just to check
  something." Same standard as TCGplayer below, for the same reason:
  a real income account that a project like this must never put at risk.

**Permitted hosts - only these.** Anything else needs explicit permission
asked for before any code is written.

| Host | Granted | For |
|---|---|---|
| `tcgcsv.com` | from the start | all price and catalog data |
| `api.pokemontcg.io` | 2026-08-16 | Pokemon card image lookup |
| `images.pokemontcg.io` | 2026-08-16 | the image files themselves |

pokemontcg.io is a free community project, so it gets the same etiquette
as tcgcsv: descriptive User-Agent, paced requests, and never re-fetching
what's already been fetched. Its catalog is pulled once and matched
offline rather than queried per card, and each image is fetched once then
served from our own disk - so the browser never contacts it at all. This
permission covers Pokemon images specifically; it is not a general
licence to add image providers. One Piece has no source yet and needs its
own conversation.

- **Never scrape tcgplayer.com.** Their terms prohibit crawling without
  permission. No HTML fetching, no headless browser, no "public search API"
  calls. Not for one card, not as a fallback, not behind a flag.
- **Never automate a login to TCGPlayer or eBay.** Inventory arrives as a CSV the owner exports by
  hand from the Seller Portal and drops in `inbox/`. That manual step is the
  design, not a gap to close.
- **All outbound traffic goes to tcgcsv.com only.** If a task seems to require
  hitting TCGplayer directly, stop and ask rather than finding a way.
- **No product images. Not from any TCGplayer host, in any component.**
  Reinforced 2026-08-15 after the front end shipped with result thumbnails
  bound to `products.image_url`, which points at
  `tcgplayer-cdn.tcgplayer.com` - roughly 50 browser requests to a
  TCGplayer domain per search. That is a violation of the rule above even
  though tcgcsv publishes the URL and documents it, and even though it
  isn't scraping. **"Only tcgcsv" means only tcgcsv - a CDN subdomain is
  still TCGplayer, and the intent of the rule is zero traffic to them, not
  zero *crawling* of them.** Do not reintroduce images from that host
  under any framing: not lazy-loaded, not one-at-a-time on click, not
  proxied through our own API (worse - that makes it server-side and
  automated). `ItemSearchResult` in `web/src/app/api.models.ts`
  deliberately omits the `imageUrl` field so it cannot be bound to an
  `<img>` by accident; the API still returns it, which is harmless because
  returning a string fetches nothing. If images are ever genuinely wanted,
  they need a source that is not TCGplayer, and that is a decision for the
  owner to make deliberately - not an implementation detail.
  **Outbound product *links* remain fine and are a core feature:** an
  `href` issues no request. Nothing is fetched unless the owner clicks it,
  which is them visiting TCGplayer in their own browser, as they would
  anyway.

If asked to add scraping, say what it risks before writing it.

## Data invariants

Domain facts the owner supplied (sub-types trade separately, sellers
price-park) plus engineering rules derived from them.

- **Prices key on `(product_id, sub_type_name, snapshot_date)`.** Pokemon cards
  exist as Normal / Holofoil / Reverse Holofoil of the same product and trade
  at different prices. Never collapse sub-types or aggregate across them.
- **`price_snapshots` is append-only.** Inserts use `ON CONFLICT DO NOTHING`.
  Never change this to `DO UPDATE`. Re-running a pull must be safe; a bad run
  must never overwrite a good observation. The history is the whole asset.
- **Never compute on `high_price`.** Sellers "price park" — listing at absurd
  values so nothing sells. Store it, ignore it. Trend on `market_price`, with
  `mid` then `low` as fallback (see `PriceSnapshot.trendablePrice`).
- **All-time high is computed on demand, never stored.**
  `MAX(COALESCE(market_price, mid_price, low_price))` in
  `item_all_time_high()`. Not an incrementally-updated table: that would
  only compare against *new* rows, so a backfill turning up an older,
  higher price would leave it silently wrong. Recomputing is always
  correct. Uses the mid/low fallback deliberately - a MAX over all history
  has no anchor-bias risk, unlike the two-point comparisons in
  `item_return`/`set_return`, which withhold it on purpose.
- **A set's price/return covers its singles only - never sealed
  product.** `set_return()`/`set_value()` hardcode `is_sealed = FALSE`
  internally; it is *not* a caller-adjustable parameter. Sealed product
  stays fully trackable individually via
  `item_return()`/`item_all_time_high()` - it just never contributes to a
  set aggregate. Sealed items had been dominating those numbers (one
  $8,441 display case was a third of a set's "total value"; Paldean Fates
  went $25,100.93 -> $2,792.05 once fixed). See Part 12 and Part 14.
- **Smoothing is a MEDIAN, never a mean** - `item_movers()` since
  2026-08-18, `game_set_overview()`'s despiking from the start. A mean
  damps real moves and leaks prices across a step: measured live, it
  reported Lillie 125a/156's genuine $41.10 -> $123.32 step (+200.0%) as
  +80.0%, and called Flaaffy's current price $34.45 when the card had
  never traded there. On the same data a median reports both exactly, and
  takes the Charizard Star one-day artifact to 0.00% rather than a damped
  -25.17% that still reached the board. Prefer odd window sizes: at odd
  sizes the median is an actually-observed price, at even sizes
  `percentile_cont` interpolates and can invent one. Part 24.
- **A reading that swings hard and reverts the next day is
  replaced with the median of its neighbours, and the row says so.**
  Decided 2026-08-18 - the owner's framing was "if something swings -80%
  and then picks right back up at its normal price then that is probably
  an error," explicitly *not* a request to smooth every correction. The
  test keys on PERSISTENCE, not magnitude: a real move leaves a new level
  behind it, an error leaves the same level either side, so no threshold
  on "how big a real move may be" is ever needed. Four things are
  load-bearing:
    * **Median, never mean** - same reasoning and evidence as the rule
      above; do not re-derive it.
    * **A dollar gate**, because the effect is heavy-tailed: 4,700 events
      in 90 days, but 15 of them carry 99.4% of the distortion. Default
      $10.
    * **`price_snapshots` is never written.** Substitution happens at read
      time in `game_set_overview()`. Append-only is not negotiable.
    * **It is a visible, defaultable-off toggle and every affected row is
      marked.** It moved EX Dragon Frontiers from -20.91% to +8.43% on one
      substitution; a correction that large which does not announce itself
      is worse than either honest alternative. A correction you cannot
      switch off is a correction you cannot check.
  Two structural limits, both in Part 24: the newest date can never be
  despiked (its next day has not been observed yet), and a 3-window median
  catches one-day impulses only. Widening it to catch two-day runs was
  **considered and decided against** - median-5 over Flaaffy (85475)
  returns $1.69, erasing a genuine two-day move entirely. Real two-day
  steps are too common for that trade; do not reopen it without new
  evidence.
- **A set-level percentage is shown with its biggest single
  contributor, never alone.** Measured 2026-08-18: 44 of 283 sets have
  over half their 30-day movement coming from one row, and the worst case
  (Base Set Shadowless, +59.96%) was 86.6% one oscillating card. The
  metric itself is unchanged - `set_return()`'s definition is an decision and `game_set_overview()` reproduces it exactly - but a bare
  percentage invites a reader to treat a one-card artifact as a set-wide
  verdict. Share is computed against GROSS movement (sum of absolute
  deltas), not net: net passes through zero and produced four-digit
  percentages on sets whose gains and losses cancel. Part 23.
- **`product_id` rides on every row through every transform.** Outbound links
  to TCGplayer are a core feature; an aggregate that loses the ID is a bug.
- **The live pull's `snapshot_date` comes from the MIRROR, not any clock**
  (`PriceSource.dataAsOf()`, reading tcgcsv's `last-updated.txt`). The live
  price endpoints carry no date - they answer "what are the prices now" - and
  tcgcsv refreshes at ~20:00 UTC, so before that refresh "now" is still
  yesterday's data. Dating it from our own clock is what wrote the phantom
  day on 2026-08-20. **Do not "simplify" this back to `LocalDate.now()` of
  any zone**: the clock answers when we asked, which is a different fact from
  what we got. The archive path never needed it because its URL names the
  date. A parse failure refuses the run rather than falling back - and that
  is safe because missing a day costs nothing (gap catch-up backfills it).
  The skip test is `hasDateCovered()`, which accepts either ingest source,
  since the mirror's date is usually one the archive already filled.
- **Where a clock IS wanted, it is always `LocalDate.now(ZoneOffset.UTC)`,
  never host-local.** Decided 2026-08-12. `BackfillArchive` computes its end
  date this way and `App` its log line (`ProductPullTest` did too, until Part
  13 deleted it); `App`'s `snapshot_date` no longer comes from a clock at all
  - see the rule directly above. Matching the archive
  backfill's own day boundary and
  the container's default clock - so `snapshot_date` means the same thing
  across the whole table's history, and `CURRENT_DATE` in raw SQL always
  agrees with it. If a new place ever needs "today," use `ZoneOffset.UTC`
  explicitly - bare `LocalDate.now()` is the bug this fixed.
  (One pre-fix run is stamped under the old convention and was left
  alone on purpose - append-only means it was valid when written.)
- **Percent change returns null on a zero or negative starting
  price.** Do not "fix" it to 0 or infinity. Lives in SQL only
  (`CASE WHEN start_price > 0 ...`); there is no Java implementation.

## Architecture intent

Design decisions, revisable - though the ones the owner set are called out
in the text where it matters.

`PriceSource` and `InventorySource` exist so the data sources are swappable.
TCGCSV has no per-condition pricing and no uptime guarantee; a paid provider
may replace it later. Keep provider specifics inside the implementations —
nothing above them should know the mirror exists.

Future: Angular front end, then cloud. Both talk to our own API, never to the
mirror directly (it has a restrictive CORS policy and asks that data be
ingested server-side).

**That API now exists** (`com.cardtracker.api.ApiServer`, added
2026-08-14 - see DEVELOPMENT_HISTORY.md Part 16 and README "Read API").
Rules it establishes, worth keeping:

- **It is read-only, and should stay that way.**
  *The community showcase needs writes. "Ingestion stays separate from the
  API" is the part actually worth keeping; "no endpoint ever writes" is
  not a rule the owner set.* No endpoint writes.
  Ingestion lives in `App`/`BackfillArchive` where the append-only
  guarantees are; an API that can write is an API that can violate them.
- **Handlers call SQL functions, not inline SQL.** Every method on
  `QueryRepository` is a thin call into `sql/functions.sql`. That keeps
  query logic verifiable directly in psql - which is how this project
  checks its own numbers - and stops the API and hand-written analysis
  from quietly diverging. New endpoint, new SQL function.
- **A bulk SQL function that restates a per-item one must be
  cross-checked against it, not assumed equal.** `game_set_overview()`
  recomputes `set_value()`/`set_return()` for every set in one pass
  (302 groups; per-set calls would be 604 round trips). Two definitions of
  the same number is exactly what "handlers call SQL functions" exists to
  prevent, so the duplication is only acceptable with a check:
  `sql/checks/set_overview_matches_primitives.sql` compares all six shared
  columns with `IS NOT DISTINCT FROM` (so null matches null). Re-run it
  after editing either side; every column must read `t`.
- **Window bounds go in scalar `DATE` variables, never a CTE
  sub-select.** In `item_movers()` and `game_set_overview()` both. From a
  sub-select the planner cannot fold them to constants, misses
  `idx_snapshots_date` and aggregates far more of the 41M-row table than
  needed - measured at ~5x and ~6GB spilled to temp files.
- **Reads go through `QueryRepository`, not `PriceRepository`.** The
  latter is write-only by design; keeping them apart stops ingestion and
  the API surface from coupling.
- **One DB connection per request** (`ApiServer.withRepo`). A JDBC
  `Connection` is not thread-safe and Javalin is multithreaded - sharing
  one corrupts results intermittently instead of failing cleanly. That
  method is the single place to add a pool if this ever leaves localhost.
  **Measured 2026-08-20, so the ceiling is no longer hypothetical:** 250
  concurrent requests are served fine; at 300 mixed requests 50 of them 500
  with `FATAL: sorry, too many clients already` against Postgres'
  `max_connections = 100`. A realistic page (50 thumbnails at once) passes
  comfortably and the server recovers on its own, so this is a shipping
  blocker, not a local one. Adding a pool means adding a dependency - the
  owner's call, not a silent one.
- **`high_price` is absent from every response** (owner's rule, made structural), and `PricePoint` has no
  field for it - the "store it, ignore it" rule made structural rather
  than left as something a future chart author must remember. Same for
  `trendable_price`, which is returned precomputed so the
  market->mid->low fallback can't be re-derived wrong.
- **Search returns products, not (product, variant) rows.** One hit per
  product carrying `variant_count`; `item_variants()` supplies real
  per-variant prices. Anything that puts a single "price" on a
  multi-variant product violates the sub-type invariant above.
- **Search parses "name + number" from one string** (owner, 2026-08-14).
  `item_search('Charizard 004')` splits into name + card number; `#` forces
  the split. Two rules worth not breaking: (1) the last token becomes a
  number only if it contains a digit, which is what keeps `Charizard ex`,
  `VMAX` and `GX` working as names; (2) if the parsed reading returns zero
  rows, the whole string is retried as a plain name. That fallback is not
  optional polish - the last-token rule genuinely guesses wrong on cards
  like `Nemona - Pikachu 42` (real card, number `180/198`), and without
  the retry those return nothing. Number matching goes through
  `normalize_card_number()`/`card_number_digits()`, never string equality:
  Pokemon puts the card number *before* the slash (`004/102`) and One
  Piece *after* the dash (`OP17-028`), so a generic rule would silently
  match the set size or the set code instead. The digits-only match is
  allowed only when the typed number has no letters, otherwise `XY67a`
  would also match `XY67`.
- **A variant label is shown only when it adds something the name
  doesn't already say** (owner, 2026-08-19, replacing their own
  2026-08-18 rule that labelled every variant loudly). `variant_label()` derives the label FROM
  the product name, so 78.7% of the time it repeated words already on
  screen beside it. The test is containment - `variantLabelAddsInfo()` in
  `web/src/app/variant-label.ts` - and it cannot mis-fire across cards
  because the label always comes from the very name it is tested against.
  What survives is the ~21% that reformats rather than repeats:
  `Night Stretcher - 2025 (Riley McKay)` -> `2025 World Championship`.
  **Styling is deliberately quiet** - an ordinary chip beside card number
  and rarity, because it is identity, not a warning. **`--warn` has been spent, and not
  on this chip:** it belongs to a genuinely borrowed image, and since Part 29
  it marks exactly that - `.art-warn-band` on the detail artwork, driven by
  `card_image_provenance()`. This chip stays quiet.
  Three internals are load-bearing, all bugs first: a trailing bracket
  faces the same validity test as a parenthetical (`is_variant_word()`,
  shared by both - otherwise all 26 `Unown [A]`..`[Z]` get a one-letter
  "variant"); a trailing card number is stripped before extraction
  (`... [Professor Magnolia] - 223`); and `is_variant_word()` is mirrored
  in Java as `ImportCardImages.isVariantWord()` so the rule deciding a
  row's label and the rule deciding its image cannot drift - change both
  or neither, then run
  `sql/checks/is_variant_word_matches_its_java_port.sql`. **Both trim their
  own argument** (Part 30): relying on callers to btrim first left 81 of
  2,547 real tokens disagreeing once padded, and note bare `btrim(x)` strips
  spaces only where Java's `trim()` also takes tabs and newlines - the
  character set in that function is deliberate, not decoration. Parts 22, 25, 26.
- **Search results must carry `card_number` and `rarity`.** Added
  2026-08-14 after the owner pointed out a gap: a set commonly reprints
  one card at several rarities, and those are different markets. SV03
  Obsidian Flames has four "Charizard ex" - 125/197 Double Rare $5.65,
  215/197 Ultra Rare $22.35, 223/197 Special Illustration Rare $108.58,
  228/197 Hyper Rare $37.37. Without these fields a result list shows
  four identical rows spanning a 19x price range. Read from
  `extendedData` via `extended_field()`, **not** parsed out of the name -
  TCGplayer appends the number to the name only sometimes (5,276 of
  34,435 singles), while `extendedData` carries `Number` and `Rarity`
  for 100% of singles in both tracked games (verified 2026-08-14). Any
  new endpoint returning product identity should carry them too.
- **CORS is scoped to `localhost:4200`.** The data isn't secret, but no
  site a browser happens to visit should be able to query it.

## Multi-game support

Which games get pulled is **data, not code**: `tracked_categories`
(schema.sql) lists the category_ids `App`/`BackfillArchive` pull. Adding a
game is an INSERT. Currently Pokemon (3) and One Piece (68).

- **Do not assume a quirk found in one game generalizes.**
  Code-card exclusion is gated on `categoryId == TcgCsvUrls.POKEMON`
  specifically, because it's a Pokemon packaging quirk, not an industry
  fact. A new game's quirk gets its own condition alongside it, never a
  broadened check. Same caution applies to `is_sealed`'s classifier -
  spot-checked on Magic and One Piece, not verified at Pokemon's depth.
- **`ingest_runs.source` is category-scoped** (`tcgcsv-3`,
  `tcgcsv-archive-68`) so each game has independent idempotency and
  failure tracking; one game failing never masks another.
- **`Throttle` is one shared instance across all categories** in
  a run - the 9000-request breaker is a whole-process limit, not per
  game. `App` logs each category's own count as a delta.
- **Watch the 10,000/24h ban ceiling as games are added.** One
  Pokemon-sized category is ~436 requests/day.

Background and the verification behind all of this: DEVELOPMENT_HISTORY.md
Part 15.

## Environment

Java 21, Maven, Postgres 16 via `docker compose up -d`, macOS. Schema
loads from `sql/schema.sql` on first container boot. Rebuild with
`mvn package` after any source change - everything runs the jar, not the
source. Setup details are in README.

**Do not run `mvn package` while a long-running job is using the jar**
(an import, a backfill). It overwrites the jar in place and Java loads
classes lazily, so a running process can die partway through.

**On-demand pulls, no scheduled job (decided 2026-08-13).** Nothing reads
this data unattended yet, so a daemon would mean Docker Desktop parked in
memory for no one. Run the jar by hand when you want fresh data.

**Missing a day costs nothing** - that's why on-demand is safe. tcgcsv's
archives persist back to 2024-02-08 and `App` runs gap catch-up
unconditionally on every run, so skipping a week just means the next run
backfills those seven days. A day with no pull is a day with no *new*
row, not a lost one.

**The trigger for a scheduled daemon is hosting, not a front end.** A
local front end changes nothing - whoever opens it can trigger the pull.
Unattended freshness only matters once someone *other than the owner*
loads the app. (Now that shipping is the goal, this is approaching.)
The LaunchAgent template is checked in at `com.cardtracker.daily.plist`,
built and verified 2026-08-13, then deliberately unloaded.

**`App` is idempotent per day and self-healing:** it skips the fetch if
today (UTC) already has a successful run, and catches up missing prior
days before pulling today. Both verified end-to-end against an isolated
scratch database, not just by code review - see DEVELOPMENT_HISTORY.md
Parts 9 and 10.

## Commit message style (decided 2026-08-14)

Owner asked for tighter commit bodies that don't re-narrate what
DEVELOPMENT_HISTORY.md already covers.

Keep commit message bodies tight and scoped to *this* change - what changed
and why, enough for `git log`/`git blame`/a bisect six months from now.
Don't re-narrate the full reasoning journey in the commit body when
DEVELOPMENT_HISTORY.md already carries it in more depth - that duplication
crept in over this project's early commits (several bodies ended up
reading like mini-essays, overlapping real estate with the dev history
doc). Point to DEVELOPMENT_HISTORY.md's relevant Part for the deeper "how
we got here" narrative instead of repeating it. Concrete numbers/impact
are still worth keeping in the commit body when they exist (e.g. "810,451
rows deleted," "$25,100.93 -> $2,792.05") - that's the kind of thing a
diff alone won't show and is genuinely useful at commit-message scope, not
narrative bloat.

## Known unknowns — verify before trusting

**One genuine unknown remains.** The Seller Portal CSV's columns have never
been seen. `InventorySource` is an interface with no implementation on
purpose - do not guess the columns; ask for a sample export first. Whether
it carries a SKU ID or only a product ID decides whether per-condition joins
are possible at all.

Two former unknowns are settled and should not be re-litigated:

- **tcgcsv field names are correct** (verified 2026-08-12 by direct curl).
  The live response also contained a genuine price-parked row - `marketPrice`
  ~70 against `highPrice` 9999 - which is what justifies the `high_price`
  rule above. Names live in `TcgCsvPriceSource`.
- **`product_id` is stable across time**, so long-range trends and
  all-time-high are safe to trust. Checked 2026-08-13 against the earliest
  archive: of 24,891 overlapping ids only 37 (0.15%) changed `group_id`, all
  of them catalog re-filing of promos/energies with identical card names, and
  `price_snapshots` keys on `product_id` alone so the churn never touches
  price history. Method and the inspection of all 37: DEVELOPMENT_HISTORY.md
  Part 6.

## Excluded products and sealed classification

- **Code cards are excluded from ingestion entirely** - not
  filtered at query time. They're digital redemption codes that crater
  toward $0 and wreck set-level aggregates. Classified by name prefix
  ("Code Card...") in `App`, Pokemon only. The 1,253 already backfilled
  (810,451 price rows) were deleted.
- **`excluded_products` is a standalone table**, independent of
  `products`, and this matters: `BackfillArchive` only ever sees a bare
  `product_id` from archives, never a name, so once an excluded product's
  row leaves `products` there'd be no way to know to skip it. Records
  `category_id` too, so an exclusion found for one game is never mistaken
  for a universal rule. Reuse this table for any future exclusion -
  `reason` is free text for exactly that.
- **`is_sealed` is set at ingest from `extendedData`** (a real
  single always carries a `Number` field; sealed product never does) -
  data-driven, not name-guessing, and computed in
  `TcgCsvPriceSource.products()` rather than re-derived in SQL later.

Why each of these exists, with the numbers: DEVELOPMENT_HISTORY.md Part 12.

## Backlog

- **Classify the remaining `unknown` image qualifiers - owner's call, one
  INSERT each.** Part 26 recorded `card_images.stripped_qualifier` but stored
  no verdict, concluding no mechanical test could say whether the picture was
  therefore wrong. Part 27 found one it had not tried - **one pokemontcg.io
  card is one picture, so ask who else claims the same `source_id`** - and
  then found that test's blind spot: **sole-claimancy proves no other product
  took the picture, never that the picture matches the physical card.**
  `Bronzong - SWSH091 (Prerelease)` was a sole claimant showing an unstamped
  promo, confidently mislabelled `own_art` until the owner pointed out that
  prerelease cards almost always carry a stamp.
  So two signals decide it, and the second cannot be derived:
  `card_image_provenance()` combines the structural test with
  **`image_qualifier_class`**, a table of the owner's domain calls -
  `identity` (catalog holds this card in its own right, e.g. `delta species`
  -> `Aerodactyl δ`, `team plasma`) or `treatment` (a stamp/foil over a base printing, e.g.
  `prerelease`, `staff`, `cosmos holo`, `winner`, and the 46-strong tournament/placement family). A treatment is `base_art` whatever its claimancy.
  **Adding a classification is an INSERT, not a code change**, and an
  unclassified qualifier falls back to the structural test rather than being
  guessed at. Current state: 4,898 `base_art`, 21,113 `own_art`, **155
  `unknown`**. 129 qualifiers classified, each an INSERT with no code change,
  and between them they corrected **47 sole claimants** the structural test
  had confidently cleared. Three family calls account for most of it: the
  tournament/placement block (44, regex-found then reviewed), the **World
  Championship Decks block (77 player names, selected STRUCTURALLY - every
  card carrying the qualifier must be in that group, because no string rule
  reliably recognises a person's name)**, and `prime`/`special` as identity.
  All seeded as explicit word lists, never runtime patterns. Remaining: a
  scatter of a few cards each across promo sets.
  **Spelling variants are handled at comparison time, not at extraction.**
  `ImportCardImages` records the stripped word verbatim and should keep
  doing so - TCGplayer genuinely sells both `Semi-Finalist` and `Semi
  Finalist` printings, and normalising there would destroy a true fact to
  tidy a lookup table. Instead `normalize_qualifier()` (functions.sql)
  lowercases, maps hyphens/underscores to spaces and collapses whitespace,
  and `card_image_provenance()` joins the vocabulary through it.
  **`image_qualifier_class` must therefore store already-normalised
  qualifiers** - two rows differing only by a hyphen would both match one
  image and duplicate it in the output. That is an invariant in the check
  file, not a convention. Normalisation is conservative on purpose: no
  stemming, so `regional championship` and `regional championships` remain
  separate until someone decides otherwise. Known imprecision left at 3
  rows: an `identity` qualifier sharing with an unqualified claimant reads
  `base_art` though both may be listings of one card (`Kyurem (Team Plasma)`
  / `Kyurem - 31/116`) - see Part 27 addendum. Verdicts are
  **computed, never stored**: a later import, or one more row in the
  vocabulary table, would silently invalidate a stored value - the same
  argument that keeps all-time-high computed. Invariants (every column must read
  `t`): `sql/checks/card_image_provenance_invariants.sql`. `base_art` is
  surfaced in the UI as of Part 29; `unknown` deliberately is not, because an
  undetermined verdict is an open question rather than a caution. Full reasoning:
  DEVELOPMENT_HISTORY.md Part 27 and its addendum.
- Inventory CSV ingest, once a real export exists
- Inventory + price join views
- **Community art showcase (owner's idea, 2026-08-16)** - themed voting
  prompts ("best beach vibes", "favourite darker-theme art") over card
  art, showcasing what wins each period. See DEVELOPMENT_HISTORY.md
  "What's still open" for the full note. **Read it before building any of
  this**: the owner confirmed 2026-08-16 that the app is meant to ship, so
  a multi-user version is in scope, not a violation. The one genuinely
  open question is card-art redistribution - showing Pokemon art publicly
  is not the same act as showing it in a local tool.
- **Oscillating `market_price` on thin markets - needs an decision.** Found building the set overview (Part 23). Some cards'
  `market_price` flips between two far-apart values for weeks at a time -
  Base Set (Shadowless) Charizard 1st Ed Holofoil sits at ~$250 or at
  exactly $10,000, while low/mid/high stay parked at $100,000. It is not a
  one-day spike (it holds $240 for two consecutive days), so neither
  `item_movers()` smoothing nor Part 24 despiking touches it. The effect is
  large: 44 of 283 sets have over half their 30-day movement from a single
  row. The set screen discloses this rather than correcting it. The open
  question is whether such readings should be flagged or treated as
  suspect at ingest - a domain call of the same kind as the code-card
  exclusion, and it affects Movers and all-time-high too. See
  DEVELOPMENT_HISTORY.md Part 23.
- One Piece card images - no permitted source exists yet; needs its own
  conversation before any code.

Completed items aren't tracked here - git history and
DEVELOPMENT_HISTORY.md carry them, and DEVELOPMENT_HISTORY.md's "What's
still open" is the fuller list of open threads.

