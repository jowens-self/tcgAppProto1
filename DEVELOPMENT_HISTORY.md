# CardTracker — Development History

This is a narrative log of how CardTracker was actually built: what was
done, in what order, and — more importantly — *why*. CLAUDE.md and
README.md describe the project as it currently stands; this document
describes how it got there, including the dead ends, the bugs, and the
reasoning behind decisions that aren't obvious just from reading the code.

It's meant to be studied, not skimmed — if you want to be able to explain
this project's development to someone else, this is the document that has
the reasoning in it. New work gets appended here as it happens.

**Started:** 2026-08-13 (retroactively covering everything from project
origin through that date, then continuing forward from there).

---

## Table of contents

1. [Part 1 — Getting the project running](#part-1--getting-the-project-running)
2. [Part 2 — Proving the concept end-to-end](#part-2--proving-the-concept-end-to-end)
3. [Part 3 — Backfilling history](#part-3--backfilling-history)
4. [Part 4 — Wiring the full daily pipeline](#part-4--wiring-the-full-daily-pipeline)
5. [Part 5 — The timezone bug and the UTC decision](#part-5--the-timezone-bug-and-the-utc-decision)
6. [Part 6 — Data integrity check: product identity stability](#part-6--data-integrity-check-product-identity-stability)
7. [Part 7 — Real automation, attempt one: the LaunchAgent](#part-7--real-automation-attempt-one-the-launchagent)
8. [Part 8 — Reconsidering: the pivot to on-demand](#part-8--reconsidering-the-pivot-to-on-demand)
9. [Part 9 — Making on-demand actually efficient](#part-9--making-on-demand-actually-efficient)
10. [Part 10 — Testing rigor: proving the gap catch-up really works](#part-10--testing-rigor-proving-the-gap-catch-up-really-works)
11. [Part 11 — Version control and publishing](#part-11--version-control-and-publishing)
12. [Part 12 — First real analytics, and the code-card discovery](#part-12--first-real-analytics-and-the-code-card-discovery)
13. [Key design decisions — quick reference](#key-design-decisions--quick-reference)
14. [Recurring patterns worth internalizing](#recurring-patterns-worth-internalizing)
15. [What's still open](#whats-still-open)

---

## Part 1 — Getting the project running

**The origin.** CardTracker was first generated in an earlier, separate
chat conversation as a Java project for a personal use case: the owner
sells on TCGplayer and wanted a way to track Pokemon card price history
over time, independent of TCGplayer's own tools. That earlier session
produced a rough project skeleton and a design write-up of constraints.
This development phase began with the owner wanting to open that skeleton
in IntelliJ and "see what breaks."

**What broke.** The project was not in a working state:
- Java source files were flat in the project root rather than in Maven's
  expected `src/main/java/...` layout.
- Several classes referenced throughout the code — `Group`,
  `PriceSnapshot`, `PriceSource`, `TcgCsvUrls`, `Throttle` — didn't exist
  as files at all. They'd been referenced in the design conversation but
  never actually written to disk.

**Fix.** Reorganized into the standard Maven directory layout and
reconstructed the five missing classes by inferring their shape from how
existing code called them (method signatures, field access patterns).
Verified the result compiled using `javac` directly against manually
downloaded Jackson dependency jars — Maven itself wasn't installed yet at
this point (that comes much later, in Part 7).

**Understanding what this project actually is.** Before writing any more
code, the key question was whether the constraints from the original
design conversation were actually understood and would be respected. The
project is explicitly:
- Not a product — no users, no auth, no public-facing anything.
- Built around one hard rule: never touch tcgplayer.com directly. All data
  comes from `tcgcsv.com`, a free third-party mirror. No scraping, no
  automated login to the owner's real seller account, ever, under any
  circumstance ("not for one card, not as a fallback, not behind a flag").

This rule exists because the owner's TCGplayer seller account is real
income. The project is speculative; the account is not. Every later
decision about data sourcing had to route through this constraint.

**Researching tcgcsv's actual limits.** Rather than guess at safe usage
patterns, the actual rate-limit documentation was fetched and read
directly (`tcgcsv.com/docs`, `tcgcsv.com/faq`). Verified findings:
- More than ~10,000 requests in 24 hours "may be banned."
- At least 100ms between requests is the documented minimum (their own
  example code uses 250ms); exceeding a reasonable rate gets the IP
  throttled for 10 minutes.
- A custom, descriptive User-Agent is required — generic ones may be
  blocked outright.
- Data refreshes once daily, so there's no value in polling more than
  once a day.

These became hard-coded values: `Throttle.MAX_REQUESTS_PER_RUN = 9000`
(a circuit breaker well under the ban threshold) and
`Throttle.politeDefault()` at 750ms between requests (well over the
minimum).

**Persisting all of this.** All of the above — the "why," not just the
"what" — was written into `CLAUDE.md` specifically so it would survive
across future sessions rather than needing to be re-explained or
re-discovered every time work resumed on this project. This turned out to
be a load-bearing decision: CLAUDE.md has been read and extended in nearly
every subsequent phase of this project, and it's what let later decisions
build coherently on earlier ones instead of contradicting them.

---

## Part 2 — Proving the concept end-to-end

**Defining the actual goal.** Early on, it would have been easy to build
toward a vague idea of "price tracking." Instead, the goal was stated
explicitly and recorded so it would persist: **a market price for every
day, for every card included in the app.** Completeness over time is the
point — a missing day is a defect, not a rounding error. This single
sentence ended up shaping several later decisions (the append-only design,
the `ingest_runs` audit table, and eventually the gap catch-up logic in
Part 9).

**Design decision: how to compute "all-time high."** Before writing any
persistence code, this question came up: should the app maintain a running
"all-time high price" as a stateful, incrementally-updated table, or
compute it on demand with a query? Decided on the query approach —
`MAX(market_price) ... GROUP BY product_id, sub_type_name` run against
`price_snapshots` whenever needed — specifically because of a concrete
failure mode with the stateful alternative: if a later backfill turns up
an older price that's actually higher than anything seen so far, a
running-high table would need explicit reconciliation logic to notice and
fix itself, and that's exactly the kind of code that's easy to get wrong
silently. A query recomputed from the full table is automatically correct
after any backfill, with no reconciliation code needed at all.

**Getting Postgres running.** The project needed a real database to test
against. Docker was chosen for this (via `docker compose up -d`, with the
schema loading automatically from `sql/schema.sql` on first boot). The
first attempt to install Docker Desktop via `brew install --cask docker`
failed — it needed `sudo` to symlink `docker-credential-osxkeychain`, and
there was no interactive terminal available to supply a password.
Homebrew rolled back cleanly on its own. Providing a password on the
owner's behalf was refused as a hard rule, so the owner was asked to run
the install themselves in their own terminal — which they did.

**First real end-to-end save.** With Postgres running, a one-off smoke
test (`ProductPullTest.java`) was built to prove the full path worked for
exactly one card: fetch category → fetch group → fetch product (to get its
real name, not just an ID) → fetch its price → save all of it to Postgres.
The test card was Mew ex #232/091 from Paldean Fates (product ID 534919).
Run twice to confirm the second run didn't create duplicate price rows —
which it didn't, thanks to the `ON CONFLICT DO NOTHING` insert design
(more on why in the key decisions table below).

---

## Part 3 — Backfilling history

**The scope question.** Once a single card could be saved, the next
question was how to fill in *historical* data — data from before this
project existed. The obvious-seeming approach would be to backfill just
the cards actually being tracked. Instead, the entire Pokemon category was
backfilled. The reasoning: tcgcsv publishes historical data as daily
archive files, and each archive bundles *every* card in *every* TCG
category for that one day — there's no way to request a narrower slice.
Downloading the archive for one card costs exactly the same as downloading
it for the whole category, because it's the same file either way. Given
that, narrowing the scope saves nothing and only limits future
flexibility.

**Idempotent, resumable design.** Before running anything expensive, the
backfill tool (`BackfillArchive.java`) was built to be safe to interrupt
and re-run. It uses the existing `ingest_runs` audit table (originally
built just for logging) as a per-day completion check: before touching a
given date, it looks for a prior row with `status = 'ok'` for that date
and source, and skips instantly if found. This was verified empirically,
not just assumed — re-running the tool after some days were already done
correctly skipped them instantly rather than re-downloading.

**Execution.** Started with a small 3-day validation run to make sure the
mechanism worked end-to-end (download → extract via `7z` → parse → insert)
before committing to anything large. Once that was clean, the full range
was run: **2024-02-08 through 2026-08-11 — 916 days, 37,754,281 total
rows, 0 failures.** This ran as a long background job while other work
continued.

---

## Part 4 — Wiring the full daily pipeline

At this point `App.java` (the daily driver) only pulled prices — it didn't
know card names, set names, or category info. Card catalog data
(categories, groups, products) was wired into the daily run so every pull
also keeps the catalog itself current, not just prices. This required new
`upsertCategory`, `upsertGroup`, and `upsertProduct` methods on
`PriceRepository` — deliberately using `ON CONFLICT ... DO UPDATE` rather
than the append-only pattern used for prices, since catalog data (a card's
name, image URL, etc.) is describing the *current* state of the catalog,
not a historical observation. Only `price_snapshots` needs to be
append-only; the catalog tables don't.

**A real bug, found only by actually running the code.** The first live
run of the updated `App` crashed on the very last group (216 of 217 had
already succeeded) with a `DateTimeParseException` — `"unparsed text found
at index 27"` on a timestamp like `2026-08-12T20:00:06.1155676Z`. The
trailing `Z` (meaning "zero UTC offset") wasn't something the existing
naive-timestamp parsing code expected; it only showed up because that
particular group had just been modified by tcgcsv at the moment of the
pull. Rather than just retrying (which would have hidden the bug — the
next run's most-recently-modified group might not trigger it, or might),
the actual cause was diagnosed and fixed: strip a trailing `Z` before
parsing, since the code already treats these timestamps as naive and a `Z`
is just redundant zero-offset notation. This is a good example of a bug
that unit tests wouldn't have caught, because it only shows up depending
on *which* group happens to have been modified most recently at the exact
moment of a real pull — it needed a live run against real data to surface.

**First clean full run.** After the fix: 217 groups, 436 requests,
32,547 products, 1,973 new price rows, no errors.

---

## Part 5 — The timezone bug and the UTC decision

**Discovery.** While verifying the run above had landed correctly, a
`SELECT ... WHERE snapshot_date = CURRENT_DATE` query against the
Postgres container returned zero rows — even though the app had just
finished successfully. Investigating why revealed the actual issue: the
Docker container's internal clock runs on UTC, while the host Mac was on
CDT. At the moment of the query, UTC had already rolled over to the next
calendar day relative to the host's local time, so `CURRENT_DATE` inside
the container disagreed with what the app (using host-local
`LocalDate.now()`) had just written.

**Two options, weighed openly.** Pin the Postgres container to the host's
local timezone (so the database's notion of "today" always matches the
Mac's), or make the application itself always compute dates in UTC
regardless of host timezone (so the database's own native clock, which is
UTC by default, always agrees with what the app writes). Both were laid
out with their tradeoffs before deciding.

**Decision: option 2 — compute in UTC.** Chosen because it also happens to
match the archive backfill's own day boundary (`BackfillArchive` already
treated tcgcsv's archives as UTC-dated), so this single choice made *all*
date handling in the project consistent with itself, not just with the
container. `App`, `BackfillArchive`, and `ProductPullTest` were all
changed from bare `LocalDate.now()` to `LocalDate.now(ZoneOffset.UTC)`.

**One deliberately un-fixed artifact.** The most recent live run before
this fix (2026-08-12, 19:40 CDT, 45,156 rows) had been stamped under the
*old* host-local convention. Under the new UTC rule, that same real-world
moment would have been dated 2026-08-13. That row was **not** retroactively
corrected. `price_snapshots` is append-only by design specifically so that
a bad or differently-conventioned run can never silently overwrite a good
observation — the row was valid under the rule in effect when it was
written, it's a one-time historical quirk, and nothing downstream depends
on "fixing" it. This is the append-only principle actually being applied
under a real, slightly uncomfortable case, rather than just being a
principle on paper.

---

## Part 6 — Data integrity check: product identity stability

**Why this mattered.** CLAUDE.md had flagged an open, unverified risk
since early in the project: TCGplayer's `product_id` is the join key that
ties every price observation to a specific physical card, across the
entire backfilled history. If a `product_id` had ever been reassigned to
a *different* card at some point, that card's price history would
silently and invisibly split into two unrelated series — and every
long-range trend or all-time-high computation built on top of it would be
quietly wrong, with no error or warning anywhere.

**Why it couldn't be checked the obvious way.** The obvious check would be
comparing card *names* for the same `product_id` at two points in time.
That turned out to be impossible with the data available: tcgcsv's daily
archives (used for the historical backfill) only ever contained a
`prices` file per group — never a `products` file. This was verified
directly by downloading and inspecting the raw contents of the
2024-02-08 archive rather than assuming it based on what the backfill
code had been written to expect.

**The check that was actually possible.** Archives still contain each
`productId`'s enclosing `groupId`, even without names. So instead: every
`product_id -> group_id` pair from the 2024-02-08 archive (24,980 unique
products) was mapped and diffed against the current, live `products`
table (32,547 products, fully populated from the live pulls in Part 4).
24,891 product_ids existed in both. Of those, 37 (0.15%) showed a
*different* `group_id` now than in 2024.

**Investigating the 37, not just counting them.** A mismatch in
`group_id` isn't automatically bad — it could mean genuine reassignment
(the scary case) or just legitimate recategorization (harmless). Every one
of the 37 was individually looked up by current name. All 37 turned out to
be promo cards, code cards, basic energy cards, or special holo variants
that TCGplayer had moved into a different organizational bucket over time
(for example, into a newly-created "SVE: Scarlet & Violet Energies" group,
or into a general "Miscellaneous Cards & Products" catch-all). The card
name was identical before and after in every single case — only which
*group* it was filed under had changed. No product_id was found reassigned
to an unrelated card.

**Conclusion.** Since `price_snapshots` keys only on `product_id` (it
doesn't store `group_id` at all), this kind of catalog reclassification
churn has zero effect on price history. The open risk in CLAUDE.md was
closed out with this evidence rather than just being marked "probably
fine."

---

## Part 7 — Real automation, attempt one: the LaunchAgent

**No persisted build output existed.** Before automating anything, it
became clear that every run of the app up to this point had been compiled
ad hoc with `javac` against jars sitting in a scratch temp directory —
there was no `target/`, no `lib/`, nothing durable. That's fine for manual
testing but not something `launchd` (macOS's scheduler) could reliably
invoke unattended.

**Installing Maven.** `brew install maven` was run (no `sudo` needed,
unlike the earlier Docker cask install — Maven has no privileged
components). `mvn package` then produced a real, self-contained shaded jar
at `target/cardtracker-0.1.0.jar`, using the shade-plugin configuration
that had been sitting unused in `pom.xml` since the very beginning.

**Verifying the plist would actually work unattended.** Before trusting a
LaunchAgent plist pointing at `/usr/bin/java`, this was checked directly
rather than assumed: `env -i /usr/bin/java -version` — a completely
stripped environment, simulating launchd's bare execution context — still
resolved correctly. This mattered because `launchd` jobs don't get a
login shell's `PATH` or `JAVA_HOME`; if `/usr/bin/java` had depended on
either, the job would have silently failed every night with no warning.

**A real gap found before it caused problems.** While verifying the
LaunchAgent's dependencies, it turned out Docker Desktop itself was not
registered as a login item — only its privileged helper daemons
(`com.docker.vmnetd`, `com.docker.socket`) start automatically at login.
That means a reboot would leave Postgres not running, silently breaking
any scheduled nightly pull until Docker Desktop was reopened by hand. This
was flagged explicitly rather than left implicit; the owner chose to
enable "Start Docker Desktop when you log in" themselves in Docker
Desktop's own settings (a GUI toggle, not something safely scriptable).

**Housekeeping, found along the way.** While checking running processes
for Docker Desktop, an 18.5-hour-old *hung* process was discovered — a
leftover from the earlier failed `brew install --cask docker` attempt in
Part 1, permanently stuck waiting on a `sudo` password prompt that could
never be answered (no terminal attached). It wasn't causing active harm,
but it wasn't going anywhere either. Flagged to the owner and killed with
explicit confirmation.

**Loaded and verified.** The plist was installed to
`~/Library/LaunchAgents/`, loaded via `launchctl load`, and confirmed
registered via `launchctl list`. At this point the daily pull was
genuinely automated.

---

## Part 8 — Reconsidering: the pivot to on-demand

**The question that started it.** Once the LaunchAgent was live, a simple
question — "how much compute will this take, could it interrupt other
work on my Mac?" — led to actually checking real numbers rather than
guessing. Findings: the nightly pull itself is trivial (network-bound,
~5.5 minutes, negligible CPU). But Docker Desktop, which has to run
continuously in the background for any scheduled job to work at all, was
configured with a ceiling of 8GB RAM / 8 CPUs on a 16GB, 8-core Mac — and
was already sitting on 3.46GB of (mostly reclaimable) RAM at idle, a
non-trivial standing footprint for a background daemon serving a workload
this small.

**The pivot.** Rather than tune Docker's resource ceiling and keep the
scheduled job, the owner proposed a different framing entirely: since
there's no app front-end yet consuming this data day-to-day (the only
consumer right now is a person manually querying Postgres or running the
jar), why keep a background daemon running at all? Instead: pull fresh
data on-demand, only when actually opening the app to look at something,
and revisit real scheduling once there's an actual reason to need
"always up to date without a manual step" — i.e., once there's a real
front end. This is a good example of *not* building infrastructure ahead
of an actual need.

**Un-scheduling it properly.** The LaunchAgent was unloaded *and* the
plist file was removed from `~/Library/LaunchAgents/` — not just
unloaded. This distinction mattered: `launchd` re-scans that directory on
every login and reloads anything sitting in it, so leaving the file in
place while merely "unloaded" would have meant the job silently came back
on the very next login, contradicting the decision that had just been
made. The plist template itself was kept, just relocated to the project
root, ready to be reinstated later with two commands whenever a real
front-end justifies it.

---

## Part 9 — Making on-demand actually efficient

**The first gap in the new model.** Almost immediately, a natural
follow-up question exposed a real gap: "will it only fetch if the data is
actually missing, or does it always re-fetch?" Checking the actual code
(rather than assuming) revealed `App` had **no such check at all** — every
run unconditionally re-fetched all 217 groups (436 requests, ~5.5 minutes)
regardless of whether that day's data already existed. The existing
`ON CONFLICT DO NOTHING` insert only prevented duplicate *rows*; it never
prevented the redundant *fetch*. Under the old scheduled model this
didn't matter (it only ran once a day anyway), but under the new
"whenever you open the app" model, checking the app twice in one day would
have meant two full unnecessary pulls.

**The fix.** `App` was updated to call the same `hasSuccessfulRun` check
that `BackfillArchive` already used for its own day-level idempotency —
if today (UTC) already has a successful run logged, skip the fetch
entirely and return immediately. Verified directly: a repeat same-day run
dropped from ~5.5 minutes to under one second.

**The second, bigger gap.** A follow-up question went further: "if I skip
opening the app for 5 days, will it grab all 5 missing days when I finally
open it, or just that one day?" Checking the code again confirmed the
live tcgcsv `/prices` endpoint has **no date parameter at all** — it only
ever returns whatever is currently live. The `snapshotDate` value passed
around in the code is purely a local label stamped onto today's fetch; it
was never capable of retrieving a *past* day's prices. Under the old
scheduled model, this risk barely existed (a job running every single day
never accumulates a gap). Under the new on-demand model, it's a real,
inevitable consequence: the less often the app gets opened, the more real,
permanent gaps would silently accumulate — completely contradicting the
project's original stated goal of "no gaps, ever."

**The fix: reuse, don't reinvent.** Rather than write new gap-detection
logic from scratch, `BackfillArchive`'s existing day-loop — which already
knows how to skip anything already covered and only actually download
what's missing — was extracted out of its `main()` method into a reusable
public method, `BackfillArchive.run(start, end, repo)`. `main()` became a
thin CLI wrapper around it. `App` was then updated to check, right after
confirming today isn't already done, whether *yesterday* is covered by
either data source (a live pull or a prior archive backfill); if not, it
calls `BackfillArchive.run(EARLIEST_ARCHIVE, yesterday, repo)` — the full
historical range — before doing today's live pull. This sounds expensive
but isn't: the reused day-loop's own skip-check means every already-done
day costs one cheap indexed database lookup, not a download, so calling
this on a normal day with no gap costs almost nothing, while a real gap of
any size gets caught and filled automatically.

---

## Part 10 — Testing rigor: proving the gap catch-up really works

**Being honest about what "verified" means.** After wiring in the gap
catch-up logic above, it was tempting to call it done — the refactored
`BackfillArchive.run()` was re-tested directly and still worked, and the
new logic in `App` was just two already-proven pieces (the
`hasSuccessfulRun` check and the `run()` method) glued together with a
single `if` statement. But "I read the code and it looks right" was
explicitly distinguished from "I watched it actually happen" — the new
branch itself had never actually fired in a real run, because in the real
database, yesterday always did have data by the time this was tested.

**The owner's idea: use git branching to create a 3-day gap.** The
initial proposal was to create a git branch, leave it untouched for 3 real
days, and test the feature against it while continuing other work on
`main` in the meantime. This is worth understanding *why it wouldn't have
worked*, because it's a genuinely reasonable-sounding idea that runs into
a subtle but important fact: **a git branch is a snapshot of source code,
not of runtime state.** It has no effect on the real-world clock the app
reads (`LocalDate.now()`), and, critically, there's only *one* actual
Postgres database — every branch would read and write the same
`ingest_runs` table regardless of which branch's code happened to be
checked out. If any other work on `main` during those 3 days had run
`App` or `BackfillArchive` against that same database, it would have
silently filled in the very days meant to be left as a gap, defeating the
whole test before it even started.

**The actual plan: an isolated scratch database.** Rather than wait 3 real
days or fight with git, a second, completely separate Postgres container
(`cardtracker-db-test`, a different port, a different container, no
shared volume with the real database) was spun up using the same schema.
It was seeded with 914 synthetic "already successfully pulled" rows in
`ingest_runs` covering 2024-02-08 through 2026-08-09 — deliberately
*not* touching `price_snapshots` at all, since `ingest_runs` has no
foreign-key relationship requiring matching price data (verified by
reading the schema before assuming this would work). Three real,
deliberately-uncovered dates were left behind: 2026-08-10, 08-11, and
08-12 — a genuine 3-day gap, engineered in seconds instead of waited-out
over days.

**Execution — real code, real network calls, fake starting state.** The
actual compiled jar was pointed at the scratch database
(`CARDTRACKER_DB_URL` override) and run completely unmodified. The result
confirmed the feature works exactly as designed: it correctly recognized
all 914 seeded days as already covered (skipping them instantly with no
downloads), printed `"No data for 2026-08-12 yet - catching up via archive
backfill first..."`, downloaded real archive data from tcgcsv for exactly
the 3 missing days (45,163 / 45,161 / 45,156 rows respectively, 0
failures), and then proceeded to a completely normal live pull for
"today" — 45,156 rows across 436 requests, matching the real database's
own numbers for that same day.

**Cleanup and verification.** The scratch container was stopped and
removed once the test completed, and the real `cardtracker-db`'s row
counts were explicitly re-checked afterward to confirm they were
completely unaffected by any part of the test. This is the difference
between a feature that's *probably* fine and one that's actually been
watched working under realistic conditions.

---

## Part 11 — Version control and publishing

**No version control existed until this point.** Despite months of
accumulated work — schema design, multiple Java classes, a 37.7-million-row
backfill, working automation logic — the project had never been a git
repository at all. This was surfaced while discussing the testing idea
above, and treated as overdue independent of any specific feature.

**Local setup first.** Before any commit, the project was checked for
anything that shouldn't be committed — no API keys, tokens, or real
secrets were found; the only password-shaped string was `localdev`, an
intentional, non-sensitive local Postgres default. A `.gitignore` was
added to exclude `target/` (build output), the shade-plugin's generated
`dependency-reduced-pom.xml`, `.idea/` (IDE-local state), and OS junk
files. The first commit covered all 22 real project files.

**Git identity.** Git had auto-generated a commit identity from the
Mac's local hostname (`skullllbunnies@SkullllBunniess-MacBook-Pro.local`)
— harmless for a purely local repo, but not something that should end up
in shared history. The owner's actual GitHub-associated email
(`jowens.self@gmail.com`, tied to their professional-showcase GitHub
account) and a display name ("Jared") were set — scoped to just this
repository, not globally, so it doesn't affect any other project on this
machine. The already-made initial commit was explicitly amended (at the
owner's request) to correct its authorship, since nothing had been shared
yet and doing so kept the history clean from the very first commit.

**Publishing to GitHub.** The GitHub CLI (`gh`) was installed via
Homebrew. Signing in was explicitly left to the owner to do themselves —
`gh auth login`, completed through their own browser — since account
authentication is something that should never be handled on someone's
behalf. Before creating anything, the repo's visibility was deliberately
discussed rather than assumed: CLAUDE.md explicitly documents this project
as "not a product... nothing public," which pulls in a different direction
than a professional-showcase GitHub account might suggest. **Private** was
chosen, given the project's real (if credential-free) ties to the owner's
actual TCGplayer seller account context. The repository was created and
pushed in one step
(`gh repo create tcgAppProto1 --private --source=. --remote=origin --push`),
and its visibility was explicitly re-verified via `gh repo view` after
creation rather than just trusted from the create command's flags.

**Result:** [github.com/jowens-self/tcgAppProto1](https://github.com/jowens-self/tcgAppProto1) —
private, one clean commit, correct authorship, `main` tracking
`origin/main`.

---

## Part 12 — First real analytics, and the code-card discovery

**The long-term vision, stated concretely.** Up to this point, everything
built had been infrastructure — no one had actually looked at what the
37.7 million rows of price history could answer. The owner articulated a
concrete long-term goal: eventually, a user-facing system for running
flexible analytical queries at will — "what's the % return of this set vs.
this single over a 3-year history" was the example given. Rather than jump
straight to building a query API or front end for that, the agreed-upon
approach was to prototype the actual analytical building blocks as raw SQL
against Postgres first — cheap to do, and it reveals what the real query
shapes need to look like before committing to any API or UI design.

**Building block one: single-card % return.** Using Mew ex #232/091 (the
same card from the very first end-to-end test in Part 2) as a concrete
example, a query was built computing `(end_price - start_price) /
start_price * 100` between a card's first and last available snapshot,
using `COALESCE(market_price, mid_price, low_price)` to respect the
existing trendable-price fallback convention from CLAUDE.md. Result: a
639.99% return from 2024-02-08 ($137.00) to 2026-08-13 ($1,013.78). Before
trusting it, the days immediately surrounding both endpoints were checked
to rule out a single-day price-parking artifact — both ends turned out to
be part of a real, stable trend, not a fluke.

**Building block two: set-level aggregate return.** The same calculation
was extended across every (product_id, sub_type_name) pair in the Paldean
Fates group (376 of them), producing a mean (+96.91%) and median (+48.33%)
— a meaningful gap worth noticing on its own, since it means a handful of
big winners were pulling the average well above what a typical card in the
set actually did. The extremes (-99.11% and +952.10%) were checked by name
rather than taken at face value: the top end was real (an Elite Trainer
Box and its case, sealed product appreciating as the set ages — a
well-known real phenomenon), and the bottom end turned out to be a
different kind of product entirely: "Code Card" listings, a separate
TCGplayer catalog entry for the digital online-redemption code packed
inside some sealed product, not a physical card at all.

**Pushback on the -99.11% number, and finding a second, real bug.** The
owner pushed back on the -99.11% figure — not because the math looked
wrong, but because it didn't match their own memory of that card's price
history. That instinct turned out to be right, and led to a second real
data-quality finding (distinct from the code-card discovery itself):
pulling the full day-by-day price history for that specific product
showed its very first recorded value ($4.50) was itself an outlier — the
price cratered to $0.95 the very next day and kept drifting down before
settling into a stable band, and `market_price` (TCGplayer's more
reliable, sales-informed figure) was completely blank for that product's
first two weeks of tracking, with only thin, listing-based `mid`/`low`
data available. The $4.50 anchor was a real recorded value, not a bug in
the query — but it was recorded before the market for that brand-new
listing had actually found its footing, making it a poor choice of
starting baseline. Re-anchored to the first day `market_price` actually
existed ($0.20, three weeks later), the "true" decline was -80%, not
-99.11% — still a real, large loss (code cards do genuinely bleed value
once redeemed), just not as extreme as the naive first-snapshot
calculation suggested. This is the same "point-to-point boundary risk"
that had been flagged as a caveat back when building block one was first
built, now caught actually happening in practice on a real card, once the
owner's own memory of the market provided a check.

**Decision: exclude code cards entirely, not just filter them at query
time.** Once it was clear code cards were a structurally different kind of
product (a digital redemption, not a card), the owner made the call to
exclude them completely — not searchable, not included in any
calculation, going forward and retroactively. This raised a genuine
technical question worth answering carefully rather than assuming: would
filtering "at the request" reduce compute cost compared to filtering
later? The answer, worked out from how the pipeline actually calls
tcgcsv's API: **no** — both `App` and `BackfillArchive` always fetch an
entire group in one response; tcgcsv has no mechanism to request a
filtered subset, so the network cost is identical either way. What
filtering *before insertion* actually saves is write cost — skipping the
database `INSERT`/`upsert` calls for code-card rows — and it keeps every
future query simpler by not needing a `NOT ILIKE 'Code Card%'` clause
forever.

**The design wrinkle: `BackfillArchive` can't see names.** This connects
directly back to the finding from Part 6 — tcgcsv's archives only ever
contain a `prices` file, never a `products` file, so `BackfillArchive` has
no way to classify a product by name; it only ever sees a bare
`product_id`. That meant a simple in-memory name check (sufficient for
`App`, which fetches real product names live every day) wasn't enough on
its own — deleting a code-card product's row from `products` would
permanently destroy `BackfillArchive`'s only way of knowing to skip it in
the future. The fix: a new, standalone `excluded_products` table
(`product_id`, `name`, `reason`, `excluded_at`), deliberately independent
of `products`, so the exclusion record survives even after the excluded
product's own row is gone. `App` classifies by name live and writes to
this table as a side effect (`upsertExcludedProduct`, `ON CONFLICT DO
NOTHING`); `BackfillArchive.run()` reads the accumulated ID set once per
call and filters against it before inserting. This is a small, general
mechanism — the `reason` column is free text specifically so a future
exclusion category (sealed product, once `is_sealed` is real; anything
else that turns out not to belong) can reuse the same table rather than
needing a new one invented from scratch.

**Verifying before deleting anything.** Rather than trust the new Java
filtering logic by code review alone, it was tested directly against a
live tcgcsv API response — via `jshell`, no database involved — for the
Paldean Fates group specifically, since it was already known to contain
code cards. Result: 295 products fetched, 13 correctly identified as code
cards (an exact match to the earlier SQL-based count for that same group),
and 375 price rows correctly reduced to 362. Only after that real
verification did the actual cleanup proceed: the current 1,253 known
code-card products were recorded into `excluded_products`, then
**810,451** price rows and **1,253** product rows were deleted outright.
As a closing check, the set-level aggregate for Paldean Fates was rerun
with *no* filtering clause at all (since the rows were simply gone) and
produced an exact match to the query-time-filtered version from just
before the cleanup — 361 variants, mean +103.26%, median +50.06%, range
-66.67% to +952.10% — confirming the deletion hadn't silently changed
anything it shouldn't have.

---

## Key design decisions — quick reference

| Decision | Chosen approach | Why |
|---|---|---|
| Data source | Only `tcgcsv.com`, never `tcgplayer.com` directly | Hard rule protecting the owner's real seller account; see Part 1 |
| Price inserts | Append-only, `ON CONFLICT DO NOTHING` | History is the whole asset; a bad run must never overwrite a good observation |
| Catalog upserts (products/groups/categories) | `ON CONFLICT ... DO UPDATE` | These describe *current* catalog state, not a historical observation — unlike prices |
| All-time-high price | Computed on demand via `MAX()` query | Avoids silent staleness after a backfill; see Part 2 |
| Backfill scope | Entire Pokemon category, not per-card | Archive files bundle the whole category per day regardless — narrowing scope saves nothing; see Part 3 |
| Backfill idempotency | Per-day check against `ingest_runs` | Safe to interrupt and resume; verified empirically, not assumed; see Part 3 |
| Date handling | `LocalDate.now(ZoneOffset.UTC)` everywhere, not host-local | Matches the archive backfill's own day boundary and the container's native clock; see Part 5 |
| `high_price` field | Stored, never computed on | Sellers "price park" (list absurdly high so nothing sells) — the field is noise |
| Scheduling model | On-demand (run when opened), not a background daemon | No front-end exists yet to justify standing infrastructure; revisit once one does; see Part 8 |
| Gap detection | Reuse `BackfillArchive`'s existing skip-logic rather than new code | One indexed lookup per already-covered day is nearly free; see Part 9 |
| GitHub repo visibility | Private | Matches CLAUDE.md's stated "nothing public" intent; see Part 11 |
| Return-calculation anchor | First/last day `market_price` actually exists, not just first/last day *any* price exists | `mid`/`low` alone on a brand-new listing are unreliable — a real bug caught by the owner questioning a suspicious number; see Part 12 |
| Code cards | Excluded entirely (ingestion-time, not query-time; existing rows deleted) | Digital redemptions, not real cards — they skew set-level aggregates and aren't meaningful to search; see Part 12 |
| Exclusion tracking | Standalone `excluded_products` table, independent of `products` | `BackfillArchive` only ever sees a bare `product_id`, never a name — it needs a record that survives after the excluded product's row is deleted; see Part 12 |

---

## Recurring patterns worth internalizing

A few habits repeat throughout this project's history and are worth being
able to name, not just recognize:

- **Verify against live data before trusting an assumption.** Rate
  limits, API field names, archive contents, timezone behavior, and the
  product-identity risk were all checked directly against real responses
  or real files rather than assumed from documentation or intuition alone.
- **Test small before running large.** The backfill got a 3-day
  validation run before the full 916-day run. The Mew ex card got tested
  before the whole category was ever touched.
- **A bug found by actually running the code is worth more than one found
  by reading it.** Both the trailing-`Z` timestamp bug (Part 4) and the
  UTC/local mismatch (Part 5) only surfaced because real, live runs were
  executed — not because the code was reviewed harder.
- **Distinguish "I reasoned about it" from "I watched it happen."** The
  gap catch-up feature (Part 9) was initially only verified by code
  review; that distinction was made explicit rather than glossed over, and
  it directly motivated building the scratch-database test in Part 10.
- **When a test would risk real data, build an isolated copy instead of
  risking the original.** The scratch Postgres container in Part 10 is
  the clearest example — full realism, zero risk.
- **Question infrastructure before building it, and revisit decisions
  openly when the reasoning changes.** The LaunchAgent (Part 7) wasn't a
  mistake — it was reasonable given the information at the time — but
  once the actual cost/benefit was examined (Part 8), it was reversed
  cleanly rather than defended out of sunk cost.
- **Respect append-only even when it's mildly inconvenient.** The
  pre-UTC-fix data artifact in Part 5 is the clearest example: the
  "correct" thing to do was leave slightly awkward data alone, because the
  design principle exists precisely for moments like that one.
- **An instinct that a number is wrong is worth investigating, not
  defending against.** In Part 12, the owner questioning the -99.11%
  return — not from a data expert's position, just "that doesn't match
  what I remember" — led directly to finding a real, second bug (a bad
  return-calculation anchor point) that code review alone hadn't caught.
  The first response to "that looks wrong" should be to go check, not to
  re-explain why the math is technically correct.

---

## What's still open

- **Reusable SQL for the analytical building blocks** — the return
  calculation and set-aggregate queries from Part 12 are still hand-typed
  each time; formalizing them as views/functions (with the market_price
  anchor fix and code-card exclusion baked in) is the natural next step
  before building any API or UI on top.
- **Product categorization beyond code cards** — `is_sealed` is still
  unimplemented (always `FALSE`), which means there's currently no way to
  slice "singles vs. sealed product" the way code cards were split out in
  Part 12. The `excluded_products` mechanism is general enough to extend,
  but sealed-vs-single isn't exclusion, it's a category — probably wants
  its own column/table rather than reusing `excluded_products`.
- **Inventory CSV ingestion** — `InventorySource` is an interface with no
  implementation, intentionally, until a real Seller Portal export sample
  exists to design against.
- **Inventory + price join views** — not started; depends on the above.
- **Real scheduling** — the LaunchAgent template still exists
  (`com.cardtracker.daily.plist` in the project root) and can be
  reinstated in two commands once there's an actual front-end that needs
  "always up to date without a manual step."
- **Docker Desktop autostart** — the owner intended to enable this
  themselves in Docker Desktop's own settings; worth confirming it's
  actually on, since it matters more if scheduling is ever reinstated.
- **Front end** — the long-term architecture intent (see CLAUDE.md) is an
  Angular front end talking to a future API, never directly to the
  tcgcsv mirror, eventually exposing the kind of flexible "% return of X
  vs. Y" querying described at the start of Part 12. Not started.
