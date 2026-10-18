# CardTracker

A TCG price-history app, built by a seller and **intended to become a
commercial product**. The source is not open and is not offered for use:
see [LICENSE](LICENSE).
Pulls daily price data from the TCGCSV mirror into Postgres, append-only,
so you build a price history you own.
Started Pokemon-only; which games are actually tracked is controlled by the
`tracked_categories` table, not hardcoded. See "Tracking another game"
below.

## What it deliberately does not do

- **No requests to tcgplayer.com at all.** Not the site, not its image
  CDN, not from the server, not from the browser. Card images come from
  pokemontcg.io instead (see "Card images"), cached and served locally.
  Outbound *links* are fine: an `href` fetches nothing until you click it
  yourself.
- **No requests to eBay, ever.** Same rule, same reason: another real
  seller account.
- **No new outbound hosts without explicit permission.** `tcgcsv.com` is
  the only one. Anything else gets asked about first, before any code.
- **No automated login, ever.** Nothing here signs into a marketplace as you.
  Inventory is yours to put in: typed in by hand on the Collection screen, or,
  once built, bulk imported from a CSV you exported yourself. Neither route
  wants your credentials.
- **Currently local-only.** One person, no auth, no hosting. That's
  where it is today, not a rule about where it can go.

Traffic goes only to `tcgcsv.com`, a third-party mirror on its own
infrastructure. Your marketplace accounts are never involved.

## Setup

```bash
docker compose up -d          # Postgres on 5432, schema loaded on first boot
mvn package                   # builds target/cardtracker-0.1.0.jar
java -jar target/cardtracker-0.1.0.jar
```

Override the defaults with `CARDTRACKER_DB_URL`, `CARDTRACKER_DB_USER`,
`CARDTRACKER_DB_PASS` if you want something other than local dev creds.

## How pulls run

Scheduled, since 2026-09-30 (CLAUDE.md, "Scheduled pulls, Docker at login"):

- `com.cardtracker.docker-at-login` opens Docker Desktop at login, and
  `cardtracker-db` has `restart: unless-stopped`, so the database is back
  after every reboot.
- `com.cardtracker.daily` runs `scripts/hourly.sh` at **16:30 local**, about
  an hour after tcgcsv's ~20:00 UTC refresh. That is the regular pull, 24
  hours apart as tcgcsv asks.
- `com.cardtracker.hourly` runs the same script every hour. It pulls only if
  a tracked game is missing the day it should hold, then exports settled days
  to `cardtracker-data` and checks freshness and archive sync.

**A missed day is lost.** tcgcsv took its archive down in September 2026
(see "Backfill" below) and the live endpoints only serve the current day, so
a day nobody pulls before the next refresh is gone for good.

The three plists are checked in at the project root. To install or reload
them:

```bash
for n in com.cardtracker.docker-at-login com.cardtracker.daily com.cardtracker.hourly; do
  cp $n.plist ~/Library/LaunchAgents/
  launchctl bootout gui/$(id -u)/$n 2>/dev/null
  launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/$n.plist
done
```

**The jobs run the built jar.** Rebuild after any code change, but not with
`mvn package` while the API server or a pull is using the jar: it overwrites
the jar in place (see CLAUDE.md). Logs are in `~/Library/Logs/cardtracker/`.

To pull by hand:

```bash
docker compose up -d                       # if Postgres isn't already running
java -jar target/cardtracker-0.1.0.jar
```

## Design decisions worth not undoing

**Prices key on (product_id, sub_type_name, snapshot_date).** Pokemon cards
exist as Normal, Holofoil and Reverse Holofoil of the same product, trading at
genuinely different prices. Collapse them and every percentage you compute is
an average of unrelated markets.

**`high_price` is stored but never computed on.** Sellers "price park": list a
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

`sql/functions.sql` (loaded automatically alongside `schema.sql`) holds every
query the app runs, so any number on screen can be reproduced in psql. The
four below are the analytical building blocks worked out by hand in
DEVELOPMENT_HISTORY.md Part 12 and formalized in Part 14, all verified to
match the hand-computed numbers before being trusted.

`item_return`/`item_all_time_high` work on any individual product, card
or sealed, since the math is the same either way, and deliberately return
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

-- Portfolio-style set return: singles only, always. See CLAUDE.md
-- "A set's price/return is defined over its singles only".
SELECT * FROM set_return(23353, '2024-02-08', '2026-08-13');
-- n_variants | total_start | total_end | pct_return
--        325 |     1176.92 |   2790.15 |     137.07

-- Current (or as-of) total value of a set. Singles only, same rule.
-- "Current" is the newest day the database holds, not today. The day's pull
-- lands at 16:30, so the newest snapshot is often yesterday with nothing wrong.
SELECT * FROM set_value(23353);
-- n_variants | total_value
--        326 |     2748.94   (as of 2026-08-21, read 2026-08-22)
```

Sealed product is still fully trackable, just as an individual item, not
folded into a set total: `SELECT * FROM item_return(528040, 'Normal',
'2024-02-08', '2026-08-13')` (the Paldean Fates Elite Trainer Box) works
the same as any single card.

## Read API

A JSON API over the query functions above, so a front end never talks to
Postgres (or to tcgcsv) directly.

Read-only over PRICE data, and that part is not negotiable: nothing here
writes `price_snapshots`, and ingestion stays in `App`/`BackfillArchive`
where the append-only guarantees live. What it does write is your own data:
holdings (append-only too, so a write still cannot destroy anything), your
preferences, and each game's rules.

`/api/health` reports whether price data is still ARRIVING, per game, rather
than whether the process is up. A liveness check that answers "ok" while the
history has quietly stopped is the exact failure that went unnoticed for five
days (DEVELOPMENT_HISTORY.md Part 40).

```bash
docker compose up -d
mvn package
java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer
# http://localhost:7070  (override with CARDTRACKER_API_PORT)
```

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | Whether price data is still arriving, per game - not just whether the process is up |
| `GET /api/games` | Tracked games, with each one's rules and how many sets count as modern |
| `GET /api/items/search?q=` | Card / product search |
| `GET /api/items/{id}` | One product's identity |
| `GET /api/items/{id}/variants` | Its variants, with current price + all-time high |
| `GET /api/items/{id}/history?subType=` | Price time series for one variant |
| `GET /api/movers` | Biggest price moves over a window |
| `GET /api/games/{id}/sets` | Sets in a game, for the movers filter |
| `GET /api/sets/overview` | Every set's value and windowed return |
| `GET /api/items/{id}/image` | Cached card thumbnail (404 = no image) |
| `GET /api/holdings?asOf=` | What you hold, priced; `asOf` moves holdings and prices back together |
| `GET /api/holdings/value?days=` | Collection value and what was paid, per day (default 90, max 1095) |
| `GET /api/holdings/{id}/history` | Every recorded change to one card's holding |
| `POST /api/holdings` | Record a holding: appends, and quantity 0 is a sale |
| `GET /api/settings` | Your preferences (the defaults until you save any) |
| `PUT /api/settings` | Save them; every field required |
| `PUT /api/games/{id}/rules` | A game's modern cutoff and early-pricing days; answers with the new set counts |

Search takes `q` (required), plus optional `game` (category id),
`sealed=false` to hide sealed product, `limit` (default 25, capped at 200)
and `offset`.

**`q` accepts a name, or a name plus a card number.** All of these find the
same card:

```
Charizard 004      Charizard 4        Charizard#004
Charizard #4       Charizard 004/102
```

Leading zeros never matter on either side: `4` matches `004/102`, `04/82`
and `4/100`. Interior and trailing zeros do matter, since `040` and `4` are
different cards. A number is only parsed out when the last word actually
looks like one, so `Charizard ex`, `Charizard VMAX` and `Charizard GX`
still search purely by name. Use `#` to force the split on a card whose
name genuinely ends in a number.

Set codes are optional: `Shanks 120` and `Shanks OP01-120` both find
`OP01-120`, and `Jirachi 67` finds `XY67`. But a variant letter you type is
respected: `Jirachi XY67a` returns only `XY67a`, never `XY67`.

If the parsed reading finds nothing, the whole string is retried as a plain
name, so a wrong guess is invisible rather than an empty result
(`Nemona - Pikachu 42` is a real card whose number is `180/198`).

```bash
curl "localhost:7070/api/items/search?q=charizard&sealed=false&limit=3"
# [{"productId":42382,"name":"Charizard","cardNumber":"004/102",
#   "rarity":"Holo Rare","setName":"Base Set","game":"Pokemon",
#   "isSealed":false,"variantCount":1,"latestDate":"2026-08-14",
#   "url":"..."}, ...]
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

Both fields come from `extendedData`, not from the name, since TCGplayer only
sometimes appends the number to the name (5,276 of 34,435 singles), so
relying on the name would leave Base Set Charizard with nothing to identify
it. Null for sealed product, which has neither.

**Search returns products, not variants.** One row per product with a
`variantCount`, never one row per (product, sub-type). A card existing as
Normal / Holofoil / Reverse Holofoil is one hit, because there is no
single honest "price" to put on it (see "Design decisions worth not
undoing" above). Ask `/variants` for the real per-variant numbers:

```bash
curl "localhost:7070/api/items/83514/variants"
# [{"subTypeName":"Holofoil","firstDate":"2024-02-08","latestDate":"2026-08-14",
#   "latestMarket":64.72,"allTimeHigh":68.29}, ...]
```

`subType` is required on `/history` and has no default, since picking one for
the caller would mean guessing which market they meant. `/variants` is
how you find the valid values.

```bash
curl "localhost:7070/api/items/534919/history?subType=Holofoil&from=2026-08-11"
# [{"snapshotDate":"2026-08-11","marketPrice":1013.78,"lowPrice":829.34,
#   "midPrice":1030.0,"directLowPrice":829.34,"trendablePrice":1013.78}, ...]
```

`high_price` is deliberately absent from every API response. See the
price-parking note above. `trendablePrice` is the documented
market→mid→low fallback, precomputed so a chart can't apply it wrong.

Each point also carries a `phase`: `"pre_release"`, `"early"` or `null`.
This is **launch pricing**, and it's mostly noise. Measured across 14 Pokemon
and 15 One Piece main sets released since spring 2024:
- **Before release:** preorder prices sit at about 3x where a card settles.
- **Release day:** still 1.5–2x.
- **After release:** Pokemon loses about a quarter in week one and 4–7% a week
  for the rest of the month.
- **By week 8:** both games are flat.

`"early"` is a card's first `earlyDays` (per game, 56 by default) after
release. A card's release is its set's date. The exception is a card first
priced more than 30 days after that, like a new promo filed under a
long-running promo group; its release is then its own first price. The rules
live in `card_release()` and `price_phase()`. Each card's first price is kept
in `card_first_priced` by a trigger, because looking it up per request cost
4.3s.

CORS is scoped to `http://localhost:4200` (the Angular dev server). The
API binds locally and is meant to stay there; if it ever moves, revisit
both that rule and the connection-per-request in `ApiServer.withRepo`.

## Front end (`web/`)

Angular 22 app over the API. Six screens: card / product search (find an
item, see its variants and their prices, chart one's history), Movers, Sets,
Collection, Disclosures, and Settings.

**Disclosures** tells a user what every number includes, what it leaves out
and how it is worked out. It reads the rules that are data (modern cutoffs,
early-pricing weeks, the staleness threshold, the user's own boxes) live, so
those can't drift; the fixed thresholds are written there, and it changes in
the same commit as any rule it describes.

A card opens in the same view everywhere: Search's side panel, and on Movers
right under the row you clicked, with the move that put it on the board.

**Settings** holds two kinds of thing, saved differently on purpose.
- **Your preferences** save the moment they change and live on the server
  per user (`user_settings`), so they survive cleared browser data. They are:
  - "Show what you paid", a dashed line under the collection's value;
  - the launch-pricing and flipping-prices boxes, which are the same
    settings as the boxes on Movers and Sets.
- **Game rules** take an explicit Save, because they change what every
  trend screen counts:
  - where "modern" starts, picked from the game's sets by release date;
  - how many weeks early pricing lasts.
  The answer says how many sets the new cutoff takes in, counted by the
  server's own rule.

Collection is what you own, priced. Adding is search-then-pick rather than
free text, because a holding is keyed by (product, variant) and a variant the
card does not have can never be priced - it would sit there at null value
forever. "Value on <date>" moves the holdings AND the prices back to that
day, so it answers what the collection was really worth then rather than
today's cards at old prices. Selling records a quantity of 0 instead of
deleting, and clicking a quantity shows every change ever recorded for it.

Above the table is a chart of what the collection was worth each day - what
you actually held then, at that day's prices. Hover or drag across it to read
any day. Its last point always equals the Value total beneath it, and a test
holds that true, since a chart contradicting the number under it is worse
than no chart.

A banner appears on every screen if price data stops arriving.

```bash
docker compose up -d                                              # Postgres
java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer  # API :7070
cd web && npm install && npx ng serve                             # UI :4200
```

All three are needed: the UI talks only to the API, and the API is the
only thing that talks to Postgres.

In IntelliJ, the **CardTracker** run configuration (in `.run/`) starts the
API and the UI together; the stop button stops both. Postgres comes up with
Docker on its own (`restart: unless-stopped`). The API runs from IntelliJ's
compiled classes, so it is always the current code and never holds the jar.

Structure, all standalone components with signals:

| File | Role |
|---|---|
| `api.models.ts` | TypeScript mirrors of the Java records (no `imageUrl`, see below) |
| `api.ts` | The only file that knows a server exists |
| `search.ts/.html/.css` | Search screen: query, filters, results, detail |
| `movers.ts/.html/.css` | Movers screen: window/game/set/modern filters, table |
| `sets.ts/.html/.css` | Sets screen: per-set value, return, concentration, modern filter |
| `modern-rule.ts` | The sentence explaining what "Modern only" means for the game on screen |
| `launch-pricing.ts` | The words for the launch-pricing and flipping boxes, and which chart points they keep |
| `price-chart.ts` | Hand-rolled SVG line chart, no charting library; hover or drag to read any day; optional dashed second line |
| `card-detail.ts/.html/.css` | The one card view: picture, variants, range, chart; used by Search and Movers |
| `settings.ts/.html/.css` | Settings screen: preferences and game rules |
| `disclosures.ts/.html/.css` | Disclosures screen: what each number includes and how it's worked out |
| `user-prefs.ts` | Your preferences as signals, loaded before the app draws, saved on change |
| `modern-cutoff.ts` | A game's release dates as "modern starts with" choices |
| `collection.ts/.html/.css` | Collection screen: add cards, value chart, totals, per-card history |
| `holding-totals.ts` | Collection totals, skipping unknown prices and costs rather than zeroing them |
| `chart-scrub.ts` | Which chart point sits under the cursor |

Layout is two columns: results left, detail right and sticky, so the
chart stays in view while you scroll the list. The results list scrolls
within its own 70vh box rather than growing the page. Below 800px the
columns stack.

The chart plots `trendablePrice` (the fallback the server already applied)
and drops days with no price rather than plotting them as zero. A missing
observation isn't a price of $0.

**Launch pricing** has two checkboxes: "Include pre-release" and "Include
early pricing". Both start unticked, because both are mostly noise.
- **Where they appear:** on Movers and Sets, and on a Search chart when that
  card has launch pricing.
- **One setting for the whole app,** saved with your settings on the server,
  and also on the Settings screen.
- **Unticked:** that part is left out of the Movers board, of a set's change,
  and of charts.
- **Ticked:** it comes back, drawn over a shaded band so it still reads as
  launch pricing.
- **A card that hasn't settled** is charted anyway, with a note saying
  everything shown is launch pricing, rather than showing an empty chart.

### Movers

`GET /api/movers`. All parameters optional, because the defaults that
define the screen's default view live in `item_movers()` so psql callers
get them too: 7 days, biggest gains, singles only, $1 minimum, 3-day
smoothing, 50 rows.

```bash
curl "localhost:7070/api/movers?days=7&game=3&direction=down"
curl "localhost:7070/api/movers?game=3&set=23353&direction=up"
```

Takes `days`, `game`, `set`, `sealed=true`, `modern=true`, `prerelease=true`,
`early=true`, `flipping=true`, `minPrice`, `direction` (`up`/`down`),
`smoothing`, `limit`.

**Flipping prices are left off the board unless `flipping=true` is passed.**
A card whose market price keeps jumping between two far-apart levels (listed
in `price_flips`, rebuilt daily) can land on the wrong one at either end.
When included, such a row carries `flipping: true`.

**Launch pricing is left off the board unless `prerelease=true` /
`early=true` is passed.** A card whose price at the "then" end was a preorder
or still settling is not on the board. On 2026-09-29, 28 of the 50 biggest
modern drops were cards that didn't exist ten weeks earlier, sliding the way
every new set does. Filtered before the top-50 cut, like modern.

**`modern=true` keeps only modern sets**, filtered before the top-50 cut so
the board stays full. Each game's cutoff is `tracked_categories.modern_since`:
Pokemon from 2019-11-15, the start of Sword & Shield (the SWSH promos came
three months before the first set), and every One Piece set. A set with no
release date never counts, since it can't be placed in a block. Moving the
cutoff is one UPDATE; the rule itself is `modern_group_ids()`.
`/api/games` carries each game's `modernSince`, and the set lists
(`/api/games/{id}/sets`, `/api/sets/overview`) mark each set `modern` so the
screens filter on the server's answer instead of repeating the rule.

**Rows are per (product, variant)**, unlike search which is per product.
a mover is one specific market's move, and the sub-types of a card move
independently.

**Prices at each end are the MEDIAN over the smoothing window, not single
days and not a mean.** Thinly-traded cards lurch on single sales: Charizard
Star (84198) sat at $4,000 for weeks, printed $980 for exactly one day,
snapped back, then settled near $990. Point-to-point that reads as -75% one
day and +308% the next.

This used a mean until 2026-08-18. Switching to a median is a correctness
fix, not a preference, measured on live data:

| case | mean-3 | median-3 | truth |
|---|---|---|---|
| Charizard Star, one-day artifact | -25.17% | **0.00%** | no move |
| Charizard Star, real sustained drop | -75.10% | **-75.03%** | -75% |
| Lillie 125a/156, real step $41.10→$123.32 | +80.0% | **+200.05%** | +200% |

A mean damps everything including the signal, and worse, it leaks prices
across a step: the +80% above is one day of post-step price contaminating
the "then" window. It also reported Flaaffy (85475) as $34.45, a price
that card has never traded at. A median annihilates single-sample impulses
outright while leaving steps and trends untouched.

Prefer an **odd** `smoothing`: at odd sizes the median is a price the card
was genuinely at on some day, while even sizes interpolate between the two
middle days and can report a price never observed, the exact failure the
mean was replaced for.

A median reacts faster than the mean did, so genuine one-day noise on thin
cards now shows at full size rather than damped; `minPrice` is what keeps
that off the board.

**`minPrice` defaults to $1** because percent change flatters cheap cards.
Without it the week's top decliner was Shuckle at $0.06 → $0.01 (-83%),
and four of the top twelve were sub-$1 cards moving pennies.

### Sets

`GET /api/sets/overview`. Every set of a game with what it is worth now
and how it moved. Takes `game` (omit for all tracked games), `days`
(default 30) and `asOf`.

```bash
curl "localhost:7070/api/sets/overview?game=3&days=30"
curl "localhost:7070/api/sets/overview?game=3&days=30&despike=false"   # raw
```

Takes `game`, `days` (default 30), `asOf`, `despike` (default on), and
`prerelease=true` / `early=true` / `flipping=true`. Flipping cards are left out
of the change (never the value) unless asked, and `nFlipping` counts them.

**Launch pricing is left out of a set's change by default**, though never
out of its value, since what a set is worth today is real either way.
- **What happens:** a card whose price at the window's start was a preorder
  or still settling is dropped from the comparison.
- **Why:** ME: 30th Celebration read -97.47% over 30 days on a basket of 5
  cards, all of them preorder prices.
- **`settlesOn`** is set when the set itself was still new at the window
  start. It lets the screen say "New · settles 2026-11-11" instead of showing
  a blank.

Backed by `game_set_overview()`, which computes `set_value()` and
`set_return()` set-wide in one pass. There are 302 tracked groups, so
calling the per-set primitives would be 604 round trips. Its numbers are
cross-checked against those primitives rather than assumed equal:

```bash
docker compose exec -T db psql -U cardtracker -d cardtracker \
  -f - < sql/checks/set_overview_matches_primitives.sql
```

Every column must read `t`. Re-run it after editing either side.

**Singles only.** Sealed product never counts toward a set aggregate. The
same hard rule as `set_return()`.

**Value and change are computed over different baskets, on purpose.**
Value is every single priced on the end date. Change compares only cards
priced on *both* dates, so a set gaining or losing cards mid-window can't
masquerade as a price move. The row shows `+N new` when they differ.

**Every set's percentage is shown with the single card that drove it**, and
flagged when that card is over half the set's gross movement. This is not
decoration. Measured 2026-08-18: **44 of 283 sets** have over half their
30-day movement from one row. Base Set (Shadowless) reads **+59.96%**, and
$9,750 of that $10,718 move is one card: Charizard 1st Edition Holofoil,
whose `market_price` toggles between ~$250 and exactly $10,000 for weeks at
a time while low/mid/high sit parked at $100,000.

Smoothing does not help here and was tried first: the regimes last weeks,
so `item_movers()`-style 3-day averaging returns $250 and $10,000
unchanged. Disclosure is the honest response, and the "driven by" column is
sortable so the untrustworthy rows can be found directly.

Share is measured against **gross** movement (sum of absolute deltas), not
net, because net passes through zero, and ranking by share-of-net gave four-digit
percentages for sets whose gains and losses nearly cancel.

**One-day price errors are corrected, and the row says so.** A reading
whose two neighbours agree with each other while it disagrees with both is
almost certainly an upstream error rather than a move. The test is
*persistence*, not magnitude, and it is replaced with the median of the
three days. Rows where that happened show `⤾ adjusted`; the checkbox turns
it off. Stored prices are never modified.

Median, not mean, and that is the point. On Charizard Star (84198), which
has a real step (970→4000) and a one-day artifact ($980 between $4,000
either side): median-3 removes the artifact entirely and keeps the step at
full size, while mean-3 leaves a phantom −25% *and* invents a +108% move on
the day before by smearing the step across three days.

It is gated at $10 of distortion because the effect is heavy-tailed: 4,700
events over 90 days, of which 15 carry 99.4% of the money. On the default
window today it changes nothing ($0.81 of sub-gate noise), but when a spike
lands on an endpoint it is decisive: as of 2026-08-04 it moved EX Dragon
Frontiers from **−20.91% to +8.43%** on a single substitution.

Two structural limits: the newest date can never be despiked (its next day
hasn't been observed yet), and a 3-day median only catches one-day
impulses. 20 two-day runs were missed over 90 days, including the Base Set
Charizard. See DEVELOPMENT_HISTORY.md Part 24.

## Card images

Thumbnails come from [pokemontcg.io](https://pokemontcg.io), a free
community API, **never from TCGplayer**. They're downloaded once,
downscaled, and served off our own disk, so the browser never contacts a
third party for them.

```bash
java -cp target/cardtracker-0.1.0.jar com.cardtracker.images.ImportCardImages
# or a single set first:
java -cp target/cardtracker-0.1.0.jar com.cardtracker.images.ImportCardImages --group 604
```

Resumable and safe to re-run: products with an `ok` or `no_match` row are
skipped, `failed` ones are retried. Images land in `images/` (override with
`CARDTRACKER_IMAGE_DIR`), gitignored since they're regenerable.

**Coverage is about 60%, and that's the ceiling.** Measured across our
27,807 Pokemon singles:

| Scope | Matched |
|---|---|
| All Pokemon singles | 60.5% |
| Main sets only | 73.3% |
| Established sets (SV01, Paradox Rift, Paldea Evolved) | 88–95% |
| Newest sets (Prismatic Evolutions, White Flare, Black Bolt) | 43–53% |
| World Championship / Jumbo / Prize Pack / promos | ~0% |
| One Piece | none, no permitted source |

The newest sets are worst because the upstream catalog lags releases.
Cards with no image get a placeholder, which is a normal state rather than
an error.

Matching is on name + card number + set total, with a name+number fallback
accepted **only when unambiguous**, since a wrong image is worse than none when
you're pricing inventory. ~14 KB per thumbnail, so the full catalog is
roughly 200 MB rather than the ~2.6 GB the originals would cost.

Their API is flaky: expect transient 500s, and note deep pagination is
broken past ~12,000 rows, which is why the importer fetches per set. If any
set fails, the run records **no** `no_match` rows that pass, because an incomplete
catalog would otherwise permanently mark cards as imageless.

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
categories `App`/`BackfillArchive` actually pull, seeded with just Pokemon
(3). Adding a game is a data change, not a code change:

```sql
INSERT INTO tracked_categories (category_id, name) VALUES (1, 'Magic');
```

The next `App` run starts pulling it from that day forward, no rebuild
needed. Its history before that can't be backfilled while tcgcsv's archive
is down (see "Backfill"). See tcgcsv's `/categories` endpoint or
CLAUDE.md "Multi-game support" for the full category list and important
caveats (Pokemon-specific quirks like code-card exclusion don't
automatically apply to a new game; verify before assuming).

## Backfill

> **Unavailable since September 2026.** tcgcsv took the price archive down
> ("temporarily", citing server costs and moderation burden) and every
> archive URL now returns 403 with that notice as the body. It asks clients
> to use the live category/group/price endpoints and not to fetch the same
> price file more than once in 24 hours. `App` no longer runs this as a
> catch-up (Part 42 of DEVELOPMENT_HISTORY.md). What follows documents the
> tool as it worked, for if the archive returns — check its terms first.

The mirror published daily archives from 2024-02-08 onward at
`https://tcgcsv.com/archive/tcgplayer/prices-YYYY-MM-DD.ppmd.7z`. Each one
bundles every TCG category for that day, and `BackfillArchive` processes every
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
(`source = 'tcgcsv-archive-' || category_id`). A day already recorded as
`ok` for a given category is skipped without downloading anything, so
re-running after an interruption, months later to catch up, or right after
adding a new tracked category, only processes what's missing. Ran clean on
2026-08-12: 916 days, ~37.7M rows, 0 failures (Pokemon only, at the time).

## Verified against live data

Field names in `TcgCsvPriceSource` matched the live mirror exactly when
checked on 2026-08-12 (`productId`, `subTypeName`, `marketPrice`, `lowPrice`,
`midPrice`, `highPrice`, `directLowPrice`. Categories, groups, and products
endpoints too). Confirmed again at scale by the full backfill: ~97% of the
~37.7M backfilled rows have a populated `market_price`. The remaining ~3%
being null reflects the mirror's own data, not a mapping error. See the
null-safe parsing note in `TcgCsvPriceSource.decimal`.
