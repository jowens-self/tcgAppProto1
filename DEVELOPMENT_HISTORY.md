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
13. [Part 13 — Full codebase review and cleanup](#part-13--full-codebase-review-and-cleanup)
14. [Part 14 — Formalizing the analytical building blocks](#part-14--formalizing-the-analytical-building-blocks)
15. [Part 15 — Generalizing beyond Pokemon](#part-15--generalizing-beyond-pokemon)
16. [Part 16 — The read path: lookup primitives and an API](#part-16--the-read-path-lookup-primitives-and-an-api)
17. [Part 17 — Searching by name and number together](#part-17--searching-by-name-and-number-together)
18. [Part 18 — The front end](#part-18--the-front-end)
19. [Part 19 — Movers: the screen that needed the data checked first](#part-19--movers-the-screen-that-needed-the-data-checked-first)
20. [Part 20 — Card images, from a source that wants to be used](#part-20--card-images-from-a-source-that-wants-to-be-used)
21. [Part 21 — The no_match audit: a matcher bug wearing upstream absence as a disguise](#part-21--the-no_match-audit-a-matcher-bug-wearing-upstream-absence-as-a-disguise)
22. [Part 22 — Saying which printing you are looking at](#part-22--saying-which-printing-you-are-looking-at)
23. [Part 23 — The set overview, and the one card that was the whole set](#part-23--the-set-overview-and-the-one-card-that-was-the-whole-set)
24. [Part 24 — Prices that swing and come straight back](#part-24--prices-that-swing-and-come-straight-back)
25. [Part 25 — The label that shouted the card's own name](#part-25--the-label-that-shouted-the-cards-own-name)
26. [Part 26 — Fixing the matcher, and refusing to guess the verdict](#part-26--fixing-the-matcher-and-refusing-to-guess-the-verdict)
27. [Part 27 — The test Part 26 said did not exist](#part-27--the-test-part-26-said-did-not-exist)
28. [Part 28 — Corrections that stopped at the docs](#part-28--corrections-that-stopped-at-the-docs)
29. [Part 29 — Spending the reserved warning](#part-29--spending-the-reserved-warning)
30. [Part 30 — The mirror nothing was holding up](#part-30--the-mirror-nothing-was-holding-up)
31. [Part 31 — Tests, at last](#part-31--tests-at-last)
32. [Key design decisions — quick reference](#key-design-decisions--quick-reference)
33. [Recurring patterns worth internalizing](#recurring-patterns-worth-internalizing)
34. [What's still open](#whats-still-open)

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

**A different definition of "set return" entirely.** The owner then
redefined what "the set's % return" should even mean: not an average
(equal-weighted or otherwise) of individual cards' returns, but a
portfolio-style number — sum every card's price on day A, sum every
card's price on day B, compute the % change between the two totals. This
is a genuinely different question than the mean/median approach answered:
it's value-weighted, so an expensive card or an appreciating sealed item
moves the number proportionally to its dollar size, rather than counting
the same as a 3-cent bulk common. Implemented carefully to stay
apples-to-apples: rather than summing "whatever's available" on each day
independently (which would conflate real price movement with tcgcsv
simply tracking more products over time), the sum is restricted to the
basket of (product, variant) pairs present with a real `market_price` on
*both* days being compared. This surfaced a companion concept the owner
asked for directly — "price of the set" as its own standalone idea (just
the current total, not a comparison between two dates), which doesn't
need the common-basket restriction at all, since there's no second date to
stay consistent with.

**Investigating the "9 extra" cards led to the real issue.** Checking why
9 more variants had a price today than had one on day one (360 vs. 351)
turned up a list dominated by sealed/bundle products — International
Tins, a Booster Bundle Display Case, Premium Collections — plus one real
single card. The owner immediately identified the actual problem this
exposed: **every set-level calculation so far had been mixing sealed
product in with real singles**, not just these 9. This wasn't a new,
separate bug — it was the same category of issue as the code-card
discovery, just for a different kind of non-single product, and it had
been quietly distorting numbers since the very first set-level aggregate
in this Part (the Elite Trainer Box's +952% return, which had been
flagged as "real, not an artifact" back then, was real, but it also should
never have been mixed into the same statistic as an actual card).

**Implementing `is_sealed` for real.** Unlike code cards, sealed product
isn't something to exclude outright — it's legitimate data, just a
different category that needs to be filterable. The `products.is_sealed`
column already existed in the schema for exactly this purpose (its own
comment said so) but had never been implemented; every row was hardcoded
`FALSE`. The fix: a real classifier, based on data rather than name
patterns this time — a genuine single card always carries a `Number`
field (its card number) in tcgcsv's `extendedData`, while sealed product
never does. This was verified in stages before being trusted: checked
against the full Paldean Fates catalog (37 sealed / 245 singles, manually
inspected on both sides), then specifically checked against 1999 Base
Set — the *oldest* data in the whole archive — to make sure the signal
holds across more than two decades of catalog history (booster
boxes/theme decks/starter sets correctly flagged, singles including an
error-print variant and Trainer-type cards correctly flagged the other
way), and finally spot-checked with a 20-item random sample spanning many
different sets after the retroactive database fix. Implemented once, at
the point where `Product` objects are actually built from tcgcsv's raw
JSON (`TcgCsvPriceSource.products()`), so the same computed value flows
through to both the stored `is_sealed` column and anything that needs it
in memory — not reverse-engineered from a name string later, and not
duplicated as separate SQL logic. The existing catalog (31,294 products at
the time) was corrected retroactively with a single `UPDATE`, the same
pattern used for the code-card cleanup: 27,807 came back singles, 3,487
sealed.

**The impact was not small.** Recomputing Paldean Fates' numbers
singles-only against the same portfolio-style methodology: current set
value dropped from $25,100.93 to **$2,792.05**, and the set's % return
dropped from +369.53% to **+137.07%**. Sealed product hadn't been a minor
contaminant in these statistics — a single ~$8,441 booster bundle display
case alone had accounted for roughly a third of the entire "set value."
This is the second time in this same stretch of work that a number which
was *technically* computed correctly turned out to be answering a
different, less useful question than intended, once someone actually
looked hard at what was being counted.

---

## Part 13 — Full codebase review and cleanup

**A different kind of pass.** Everything up to this point was feature work
or bug-fixing driven by a specific question or a specific number looking
wrong. This was the first time the whole codebase - every source file, the
schema, the build config - was read fresh and reviewed on its own terms,
not in service of a particular feature. Eight real issues surfaced, all
fixed in the same pass:

- **The gap catch-up call could fail invisibly.** `App`'s yesterday
  catch-up (calling `BackfillArchive.run()`) happened *before*
  `repo.startRun()` created today's `ingest_runs` row. If the catch-up
  itself threw - a full disk, a transient DB error - the exception would
  escape `main()` with zero record in `ingest_runs`, exactly the "silent
  gap" `ingest_runs` exists to prevent (see CLAUDE.md's own stated
  purpose for the table). Fixed by moving `startRun()` before the catch-up
  and wrapping both the catch-up and the main pull in the same
  try/catch/finishRun("failed", ...) block.
- **`ingest_runs` had no index behind its most-used query.** CLAUDE.md
  itself claimed the gap-catchup skip-check was "one indexed lookup, not a
  download" - that claim was false. `EXPLAIN` showed a full `Seq Scan`;
  `\d ingest_runs` showed only the primary key. This query runs once per
  day in `BackfillArchive`'s range - up to ~900 times in a single
  catch-up call. Fixed with `CREATE INDEX idx_ingest_runs_source_date_status
  ON ingest_runs(source, snapshot_date, status)`, verified live:
  `EXPLAIN` now shows `Index Only Scan`, cost dropped from 27.73 to 8.30.
- **`App` depended on the concrete `TcgCsvPriceSource`, not the
  `PriceSource` interface it was supposed to depend on.** CLAUDE.md's own
  "Architecture intent" says "nothing above [PriceSource] should know the
  mirror exists," but `App` declared `TcgCsvPriceSource source = ...` and
  called `categories()`/`groups()`/`products()`, none of which were on the
  interface - the abstraction existed on paper but wasn't actually usable.
  Fixed by adding those methods (plus `requestsUsed()`) to `PriceSource`
  and changing `App`'s declared type.
- **`ProductPullTest` had fully outlived its purpose and skipped the
  code-card exclusion invariant.** Its own docstring said it existed to
  prove the products path worked "before it's wired into the daily App
  run" - that happened back in Part 4. Worse, it called `upsertProduct`
  unconditionally, with no exclusion check, unlike `App` - a latent way to
  reintroduce excluded products if ever pointed at one. Deleted.
- **Three separate reuse gaps**, all fixed the same way: extract a shared
  piece instead of trusting three copies to stay in sync. The identical
  `env(key, fallback)` helper, copy-pasted in `App`, `BackfillArchive`, and
  `ProductPullTest`, became a single `Env.get()` utility. The
  `USER_AGENT` constant, independently defined in both
  `TcgCsvPriceSource` and `BackfillArchive`, became one `public` constant
  on `TcgCsvPriceSource` that `BackfillArchive` references directly -
  since both send this header to the same tcgcsv.com infrastructure, and
  the constant is explicitly meant to be bumped when behavior changes, two
  independent copies were a real drift risk, not just duplication for its
  own sake.
- **Two stale/dead-code cleanups.** `Change.percent()` had zero call
  sites anywhere - every percent-change calculation in this project so
  far has been ad-hoc SQL, not this Java utility - so it was deleted
  rather than left as an untested, unreferenced "maybe someday." CLAUDE.md's
  own reference to it was updated to point at the actual current
  implementation location (ad-hoc SQL `CASE WHEN`) instead. `TcgCsvUrls`'s
  class comment claimed the endpoints were "not checked against a live
  response yet" - false for a long time; README's own "Still to verify"
  section had already become "Verified against live data," and these
  exact URLs have carried the entire 37M+ row backfill.

**Method.** Rather than review a diff (there wasn't one - everything was
already committed and pushed), every source file was read fresh in full,
checked against what CLAUDE.md and the schema actually claim, and
cross-referenced against real behavior where a claim was checkable (the
index claim was verified with `EXPLAIN`, not just inferred from the code).
Each fix was compiled and smoke-tested before being called done: the
restructured `App` was re-run and still correctly hit its "already have
today" fast path; the `BackfillArchive` refactor was re-run standalone
against an already-covered day and still produced "1 already had, 0
backfilled, 0 failed"; a final grep swept for any dangling reference to
the deleted files or the old duplicated patterns. Nothing was left
half-fixed or merely reported without being applied.

---

## Part 14 — Formalizing the analytical building blocks

**Picking up where Part 12 left off, on a now-clean foundation.** With the
codebase reviewed and fixed (Part 13), the natural next step was the one
flagged as still open back in Part 12: turn the hand-typed return/set-value
queries into something reusable instead of retyping them per question.

**A hard rule surfaced immediately, before any SQL got written.** Asked
what the formalized functions should default to for sealed product, the
owner corrected the framing entirely: a set's price or return should
*never* be influenced by sealed product's value at all - not a default
that can be overridden, a categorical exclusion. Sealed items remain fully
trackable, just as individual items, never as part of "the set." This
mirrors the code-card decision from Part 12 almost exactly (exclude
entirely, don't leave it as a query-time toggle someone could forget), and
it simplified the design: no `singles_only` boolean parameter needed at
all, since there's no scenario where a caller should be able to ask a
set-level function to include sealed product.

**Four functions, in `sql/functions.sql`, loaded the same way as
`schema.sql`:** `card_return` and `card_all_time_high` operate on any
individual product (single or sealed) between two exact dates or over all
time; `set_return` and `set_value` operate on a whole group and hardcode
`is_sealed = FALSE` internally - not as a default, as the only behavior.
`card_return` deliberately requires an exact date match on both ends
(empty result rather than silently substituting a "nearest available"
date) - a deliberate choice to avoid quietly reintroducing the kind of
anchor-bias problem found in Part 12, where a substituted date could shift
which day's price actually gets compared without the caller realizing it.

**A real inconsistency caught while building `card_all_time_high`.**
CLAUDE.md's original all-time-high decision (2026-08-12) specified plain
`MAX(market_price)`, with no fallback to `mid`/`low` - directly
contradicting the general trending rule stated one line above it in the
same document ("Trend on `market_price`, with `mid` then `low` as
fallback"). This wasn't a deliberate exception, just an inconsistency that
had sat undetected in the documentation for a day. Reconciled by giving
`card_all_time_high` the fallback, on the reasoning that a `MAX()` over
the *entire* history doesn't carry the same anchor-bias risk that a
two-point comparison does (there's no single "wrong" day being picked as
an anchor - more data points only ever make a MAX() more complete, never
misleading the way an unlucky start-date pick can for a return
calculation). For this specific card (Mew ex) the two formulas happened to
agree ($1089.18 either way, since `market_price` has been populated for
its entire tracked history) - the distinction matters for products with
early coverage gaps, the same category of product that caused problems
earlier in Part 12.

**Verification before trusting any of it.** Every function was run against
data with a already-known-correct answer from the hand-typed queries
earlier in the session, and matched exactly: `card_return` for Mew ex
reproduced +639.99%; `card_all_time_high` reproduced $1089.18;
`set_return` for Paldean Fates reproduced 325 variants, $1176.92 →
$2790.15, +137.07%; `set_value` reproduced 326 variants, $2792.05. A
final check confirmed `card_return` also works correctly on a sealed item
called directly (the Elite Trainer Box, reproducing +952.10%), confirming
individual-item tracking and set-level exclusion coexist correctly rather
than one accidentally breaking the other.

**Renamed shortly after, same day.** The owner pushed back on
`card_return` accepting sealed items at all - not on the behavior itself
(splitting the math into two near-identical functions for card vs. sealed
would have been pure duplication, since the calculation doesn't differ by
category), but on the *name* implying "cards only" while silently
returning sealed results too. Renamed `card_return` → `item_return` and
`card_all_time_high` → `item_all_time_high`, and both now return
`name`/`is_sealed` as part of their output - the category is visible in
every result instead of something the caller has to already know or look
up separately. Verified again after the rename: same exact numbers for
both a single (Mew ex) and a sealed item (Elite Trainer Box) called
through the new names, old function names confirmed dropped from the live
database rather than left as orphaned duplicates.

---

## Part 15 — Generalizing beyond Pokemon

**The question that started it.** A casual "do you have anything that
isn't Pokemon?" turned out to have a real, useful answer: no data other
than Pokemon had ever been pulled, but the *architecture* had never
actually hardcoded Pokemon at a deep level - `categoryId` was already a
parameter throughout `PriceSource`, `TcgCsvUrls`, and the schema
(`categories`/`groups`/`products` all key on `category_id` generically).
The only thing Pokemon-specific was `App`/`BackfillArchive` always passing
`TcgCsvUrls.POKEMON` instead of something configurable. Checked live
against tcgcsv's real `/categories` endpoint before saying anything
definite: it covers roughly 90 categories - Magic, Yu-Gi-Oh!, Digimon, One
Piece, Lorcana, Flesh & Blood, Star Wars Unlimited, and many more,
including Pokemon Japan as its own separate category from regular Pokemon.

**A caution that reshaped the design before any code was written.** While
spot-checking Magic's product data to see whether the `is_sealed`
classifier (presence of a `Number` field) would generalize, the owner
interrupted with an important point: code cards are a Pokemon-specific
thing, and other games may have their own quirks that haven't been
discovered yet. This mattered more than it might sound - it's the
difference between building a system that assumes Pokemon's known
peculiarities are universal trading-card-industry facts, versus one that
treats each game's specific behavior as something to be discovered and
recorded per-game, not assumed. The Magic spot-check (one single card,
Darksteel Angel, correctly showing a `Number` field) was reassuring but
explicitly documented as far short of the verification Pokemon got across
its whole 1999-2026 history - a note for whoever actually pulls a new
game's data in volume for the first time, not a settled fact.

**The core generalization: `tracked_categories` as data, not code.** A new
table lists which category_ids `App`/`BackfillArchive` pull - adding a
game becomes `INSERT INTO tracked_categories`, not a code change and
rebuild. Seeded with only Pokemon so nothing about current behavior
changes until a second row is added deliberately. This mirrors the
`excluded_products` pattern from Part 12/13 almost exactly: put the thing
that changes over time in the database, not in source code.

**Code-card exclusion scoped explicitly, not broadened.** Rather than
apply the "Code Card" name check to every category (harmless in practice,
since the string is specific, but dishonest about what's actually been
verified), it's now gated on `categoryId == TcgCsvUrls.POKEMON` directly
in `App.pullCategory`, with a comment explaining why and how to add a new
game's own quirk later - a new condition alongside it, not a broadened
one. `excluded_products` gained a `category_id` column for the same
reason: an exclusion reason recorded for one game should never be
mistakable for a universal rule once there's more than one game's data
sitting in the same table.

**A real efficiency insight that fell out of the design, not planned in
advance.** tcgcsv's archive files already bundle every category for a
given day in one download - that was known since Part 3 (it's the whole
reason the original backfill covered the entire Pokemon category instead
of one card, since narrowing scope saved nothing on the download). Once
`BackfillArchive` had to handle multiple tracked categories, this fact
became directly useful: one archive download per day now serves every
tracked category's subfolder from that same extraction, so adding a
second tracked game costs zero extra downloads for backfill - only more
local file parsing, which is nearly free by comparison.

**A cascading simplification in `App`.** Generalizing to multiple
categories exposed that the old per-category "is yesterday covered"
pre-check in `App.main` (added back in Part 9) was redundant work once
`BackfillArchive.run()` had to loop over categories and skip already-done
(category, day) pairs internally anyway - calling it unconditionally is
just as cheap on a normal day as checking first and then deciding whether
to call it. Removed the pre-check entirely; `App.main` now always calls
the gap catch-up once for every tracked category up front, then pulls each
category's live data in its own isolated try/catch, so one game failing
never blocks or masks another succeeding.

**A subtler correctness fix, caught while designing the isolation.**
`Throttle` has to stay one shared instance across every tracked category
in a single run - the 9000-request circuit breaker is a whole-process
safety limit against tcgcsv's ban threshold, not something that should
reset per category. But that means `requestsUsed()` is cumulative, so
naively logging it per category into `ingest_runs.request_count` would
have made every category after the first show an inflated, running-total
number instead of its own actual request count. Fixed by snapshotting the
count before each category's pull and logging the delta instead of the
raw cumulative value.

**Migrating existing history instead of losing it.** All 919 existing
`ingest_runs` rows used the old flat `tcgcsv`/`tcgcsv-archive` source
names. Renaming the convention to be category-scoped
(`tcgcsv-3`/`tcgcsv-archive-3`) without migrating those rows would have
made 916 days of already-completed backfill and 3 days of live pulls
invisible to the new naming scheme - `hasSuccessfulRun` would have found
nothing under the new names and silently triggered a full, unnecessary
re-pull and re-backfill of everything already done. Fixed with a plain
`UPDATE` renaming the existing values before the new code ever ran against
them - not a delete, since `ingest_runs` is operational metadata rather
than the append-only price history `price_snapshots` protects, so
relabeling it is a different category of action than the deletions earlier
in this project have deliberately avoided.

**Verified by an accident of real time, not a staged test.** While
testing that the rename preserved "already done" status, the live
verification run took far longer than expected - long enough to reveal
that real wall-clock time had quietly crossed into a new UTC day
(2026-08-14) partway through this session. Rather than a bug, this turned
into an unplanned, completely genuine end-to-end validation: the gap
catch-up correctly reported "916 already had" (confirming the rename
preserved all prior history), correctly backfilled the 2 most recent days
that had only just become available as archives, and the live pull then
correctly triggered only for the actual new day - exactly the behavior
the whole gap-catchup system (Part 9) was built for, now proven under
real conditions instead of a constructed scratch-database scenario like
Part 10 used.

**One Piece Card Game actually added, same day.** The owner asked directly
whether adding a real second game felt safe - a fair question to ask
honestly rather than just assert confidence about. Before answering yes,
One Piece's real product data was checked live: the `is_sealed` classifier
held cleanly across two separate samples (a promo-era group and a recent
2026 set), matching the same "sealed has no `Number` field, singles do"
pattern already confirmed for Pokemon and Magic - three real games now,
not an assumption extended on faith. A concern about the earliest data
possibly predating the game's English release turned into a useful
clarification of two facts that had been getting conflated: catalog
metadata (`groups.published_on`) reflects a product's real release date
and can go back decades (Pokemon's Base Set: 1999-01-09, confirmed
directly against the live table), while the price *archive* itself has a
hard floor of 2024-02-08 for every game regardless of when that game
actually launched - the two aren't the same limit, and neither one was
ever an arbitrary choice made for this project specifically. With that
settled, a 3-day validation backfill confirmed Pokemon's already-covered
days were correctly skipped while One Piece's were correctly processed
from the same shared archive download - then the full range (918 days)
backfilled clean, 0 failures, followed by a live pull to populate the
catalog (archives never carry product names, the same limitation
discovered for Pokemon back in Part 6). Final state: 7,261 One Piece
products (633 sealed / 6,628 singles - nearly identical proportion to
Pokemon's own split), and - the real proof this generalization actually
works rather than just being designed to - `item_return()`, written and
tested against Pokemon data only and never touched since, correctly
computed a real return for a One Piece card with zero code changes
required.

---

## Part 16 — The read path: lookup primitives and an API

**Choosing the first screen, and correcting its name.** With ingestion,
backfill, multi-game support and the analytical functions all done, the
obvious remaining gap was that the only way to see any of this data was
typing SQL into psql. Three candidate first screens were considered
(biggest movers, set overview, single-card lookup); the owner picked
lookup, with an immediate correction: not "card search" but "Card /
Product Search." That correction matters more than wording. The codebase
had already made exactly this distinction once, renaming
`card_return`/`card_all_time_high` to `item_*` in Part 14 because sealed
product is looked up the same way a single is. Naming the new surface
"card" would have quietly re-narrowed a decision already made
deliberately, so the API is `item_*` throughout.

**Building the primitives, not the screen.** The owner's framing was to
start with "basic functionality that will for sure be needed in the
future / for other components," which pointed at the read path rather
than any particular UI: search, variants, history. That sequencing also
deferred the UI-framework choice until there was something real to
render, and made every step verifiable with `curl` instead of a browser.

**The one design decision that shaped everything: search returns
products, not variants.** A product commonly exists as Normal / Holofoil
/ Reverse Holofoil trading at genuinely different prices, and CLAUDE.md's
oldest invariant forbids collapsing them. That leaves two honest options:
return three rows for one card, or return one row that doesn't pretend to
have a single price. The second won - `item_search()` reports
`variant_count`, and `item_variants()` supplies the real per-variant
prices. This is also what makes `item_variants()` necessary rather than
convenient: `item_return()` and `item_all_time_high()` both *require* a
`sub_type_name`, and a caller genuinely cannot guess which variants a
product has. It varies per product and changes over time - Altaria
(83514) has a Holofoil going back to 2024-02-08 but a Normal that only
starts 2025-03-28 - which is why `first_date` is returned rather than
assumed.

**Set and game ride on every search hit, because names are not
identifying.** 266 Pokemon products match "charizard," and dozens are
named exactly `Charizard`. A result list of bare names would be
unusable, so `set_name` and `game` come back on every row.

**An invariant made structural rather than documented.** `item_history()`
deliberately does not return `high_price`, and `PricePoint` has no field
for it. The project has always said "store it, ignore it" (sellers price-
park, listing at absurd values so nothing sells), but that was a rule a
future chart author had to know. Omitting it from the API entirely means
they can't plot it by accident - the same reasoning that made
`set_return()` hardcode sealed product out instead of offering a
parameter. `trendable_price` is returned precomputed for the same reason:
the market->mid->low fallback is a documented rule, so the API applies it
rather than leaving each caller to re-derive it and one of them to get it
wrong.

**Two performance findings, both measured rather than assumed.** Search
is a leading-wildcard `ILIKE`, which no btree index can serve - it was a
full sequential scan of all 38,555 products on every keystroke (~30ms,
and growing with each game added). A `pg_trgm` GIN index took that to
~1.3ms. Separately, the first working version of `item_search()` ran
722ms: with the per-product variant lookup inline, Postgres computed
variant counts for all 266 matches and only then applied `LIMIT 25`.
Applying the limit in a CTE *before* the lateral join dropped it to 37ms.
Neither was predicted by reading the query; both came from running it.

**A real bug caught by re-running the same query twice.** Between two
otherwise identical test runs, the `charizard` results came back in a
different order. The cause: every product named exactly "Charizard" ties
on (rank, name length, name), leaving Postgres free to order them
however it likes. Harmless-looking for a single query, genuinely broken
for a paging API - `LIMIT`/`OFFSET` over a nondeterministic order
silently duplicates and skips rows between pages. Fixed by adding
`product_id` as a final tiebreaker, then verified directly: the same
query three times returns an identical id list, and page 1 ends at 84188
with page 2 starting at 84189, no overlap.

**Every new function cross-checked against an already-trusted one.**
Rather than trust the new SQL because it looked right,
`item_variants()`'s all-time high was checked against
`item_all_time_high()` for the same item (77.49 both ways), and
`item_history()`'s endpoints against `item_return()`'s anchors for the
same dates (137.00 -> 1013.78, +639.99%, matching the figures already in
the README). Same habit as Part 14, where the formalized functions were
checked against the hand-computed numbers before being trusted.

**A concurrency decision, and then actually testing it.** A JDBC
`Connection` is not thread-safe and Javalin serves requests on multiple
threads, so `ApiServer` opens one connection per request rather than
sharing one. A shared connection wouldn't fail cleanly - it would corrupt
results intermittently under parallel use. Rather than leave that as a
comment, 30 genuinely parallel requests across all three endpoints were
fired at once: all returned 200, all bodies parsed as valid JSON, and all
ten concurrent full-history responses returned the same 919 points with
identical prices. Worth stating the limit honestly: 30 local requests is
not load testing and wouldn't catch a subtle race needing sustained
traffic. It shows the design isn't obviously broken, which is what it was
for. (The first attempt at this test failed on its own plumbing - twelve
parallel `curl`s piped into one stream interleaved mid-JSON and produced
a parse error that looked like a server bug until the harness was fixed
to write separate files. A reminder to distrust the test before the
system under test.)

**The owner caught a real gap immediately: card number and rarity.** The
first version of search returned name, set and game - which the data
seemed to justify, since a check for duplicate (set, name) pairs among
singles returned exactly zero. The owner's domain knowledge said
otherwise: a set routinely reprints the same card at several rarities
with different numbers. Both were true, and the reconciliation is the
interesting part. TCGplayer *does* disambiguate, by appending the number
to the product name - which is why the duplicate check came back empty -
but only sometimes: just 5,276 of 34,435 singles have a number in their
name. Base Set Charizard is simply "Charizard."

Checking `extendedData` (the same blob the `is_sealed` classifier keys
on) found `Number` and `Rarity` populated for **all** 34,435 singles
across both tracked games - a far better source than parsing names. Both
are now first-class fields on search and product detail, read via a new
`extended_field()` helper so the unwrap isn't hand-rolled per caller.

The concrete case, and why this was not cosmetic - SV03 Obsidian Flames
has four cards named "Charizard ex":

| # | Rarity | Market |
|---|---|---|
| 125/197 | Double Rare | $5.65 |
| 215/197 | Ultra Rare | $22.35 |
| 223/197 | Special Illustration Rare | $108.58 |
| 228/197 | Hyper Rare | $37.37 |

A 19x spread across four rows that would otherwise have rendered
identically. This is the same pattern as the sealed-product and
return-anchor findings in Part 12: a query that was technically correct
against the data it was given, quietly answering a less useful question
than the one a person actually has. It was caught by domain knowledge,
not by review - and notably, the *first* check (zero duplicate names)
appeared to refute it. Worth remembering that a query returning "no
problem here" can be measuring the wrong thing.

**A re-runnability bug surfaced while fixing it.** Adding two columns to
`item_search()`'s `RETURNS TABLE` made `sql/functions.sql` fail to load
against any database that already had the old signature - `CREATE OR
REPLACE FUNCTION` cannot change a function's return type. Since that file
is meant to be re-applied by hand after every edit, this would have hit
the owner on the next pull, not just here. Fixed with an explicit
`DROP FUNCTION IF EXISTS` ahead of that one definition (a no-op on a
fresh boot), rather than leaving a file that only works on a virgin
database.

**Scope kept deliberately narrow.** The API is read-only - there is no
endpoint that writes, and ingestion stays in `App`/`BackfillArchive`
where the append-only guarantees live. CORS is scoped to
`localhost:4200` (the Angular dev server) rather than left open: the data
isn't secret, but there's no reason any site a browser visits should be
able to query it. Javalin was chosen over Spring Boot because this is a
handful of read-only JSON endpoints over existing SQL functions and
didn't warrant a framework; the connection-per-request method is
annotated as the single place to add HikariCP if this ever leaves
localhost.

---

## Part 17 — Searching by name and number together

**The ask.** Straight from the owner: `Charizard 004` should find every
Charizard numbered 4. Leading zeros shouldn't matter in either direction,
and `Charizard#004` should work too. The stated instinct - "take whatever
is after the space and check that for what number card they want" - was
the right shape, and survived contact with the data with two refinements.

**The data decided the matching rule, not intuition.** Checking the real
`Number` values across both games first turned out to matter a lot:

| Shape | Example | Count |
|---|---|---|
| `digits/digits` | `004/102` | 24,765 |
| `letters+digits` | `XY121` | 1,399 |
| digits only | `196` | 916 |
| `TGxx/TGyy` | `TG10/TG30` | 532 |
| `digits+letter/digits` | `098a/122` | 111 |
| `ABC12-345` (One Piece) | `OP17-028` | 6,274 |

The important find: **Pokemon puts the card number before the separator
(`004/102`) and One Piece puts it after (`OP17-028`)**. A single generic
"take the digits" rule would have matched the *set size* for Pokemon or the
*set code* for One Piece - wrong answers that would have looked plausible.
`normalize_card_number()` splits on the two cases explicitly and documents
why, rather than trying to be clever with one regex.

Leading zeros are stripped only at the start of a digit run, so `004` = `4`
but `040` stays distinct from `4` - the padding is noise, the trailing
zero is a different card.

**Refinement one: "after the space" needed a guard.** Taken literally it
breaks `Charizard ex`, which has a space and a trailing token. The rule
became: the last token is a number only if it *contains a digit*. That one
condition is what keeps `ex`, `GX`, `V` and `VMAX` working as names, since
none of them contain digits.

**Refinement two: the guess can still be wrong, so it falls back.** Some
cards genuinely end in a number that isn't their card number -
`Nemona - Pikachu 42` is real, and its number is `180/198`. Parsing would
read that as name `Nemona - Pikachu` plus number 42 and return nothing.
So when the parsed reading finds zero rows, the whole original string is
retried as a plain name. A wrong guess costs one extra query and is
invisible; without it, a card that exists would appear not to. This is the
single most important line in the feature and the least obvious one -
worth not "simplifying" away later. It also motivated writing `item_search`
in plpgsql over a shared `item_search_core`, so parse-and-retry lives in
one definition every caller gets, rather than in whichever client
remembered to implement it.

**A bug caught by testing a case the spec didn't mention.** The
digits-only match exists so a set code is optional (`121` finds `XY121`,
`28` finds `OP17-028`). But testing `Jirachi XY67a` returned both `XY67a`
*and* `XY67`, because both reduce to digits `67` - throwing away the
variant letter the user had deliberately typed, and undoing the care taken
to keep `98a` distinct from `98`. Fixed by allowing the loose digits match
only when the typed number contains no letters at all. Type a bare number
and matching stays generous; type letters and it turns strict.

**Efficiency, which was the owner's explicit question.** It stays fast
because the filters are ordered: the name predicate hits the `pg_trgm`
index first, and the number check - which has to unwrap `extendedData`
JSON - only ever runs on rows the name filter already kept. A plain name
search is unchanged at ~2-4ms; adding a number costs ~40-56ms because the
JSON unwrap has to happen before the `LIMIT` rather than after. Well
inside a 250ms search-as-you-type debounce, so no index on the extracted
number was added - that would be optimising a path that isn't slow.

---

## Part 18 — The front end

**Angular, chosen on the owner's own terms.** The documented intent had
always said Angular, and rather than quietly substituting something
lighter the tradeoffs were put honestly: a real toolchain (Node wasn't
even installed), RxJS observables showing up on the very first API call,
and more ceremony than a one-screen app strictly needs - against a
structure that scales to the movers/sets/inventory screens still to come,
and a stack worth having on a professional-showcase repo. The owner asked
directly whether there was any reason not to, got the caveats, and chose
Angular. Angular 22, standalone components and signals throughout.

**A detour worth recording: the Node install hung for 90 minutes.**
`brew install node`, run from the owner's own terminal, sat at 0% CPU with
`STAT` `S+` and no child processes - the signature of a process blocked on
a prompt, not one doing work. Because it was running in their terminal
rather than the agent's, its output wasn't visible from here at all; the
diagnosis came entirely from `ps`. After a reboot the same command run
non-interactively (`NONINTERACTIVE=1`) completed in under a minute. Worth
remembering: when a command "hangs," check whether it's waiting on stdin
before assuming it's slow.

**The one design decision that shaped the screen: the flow mirrors the
data.** Search returns products, so the UI shows products; picking one
reveals its variants, because those are genuinely different markets; a
chosen variant has a history. Flattening that - showing one price per
search result - would have meant the UI silently picking a market for the
user, which is the same mistake the API was built to avoid. The one
concession to convenience is that selecting an item auto-opens its most
valuable variant, since for a card with a $5 Normal and a $77 Reverse
Holofoil that is overwhelmingly the one being looked up.

**Invariants enforced by omission, again.** `PricePoint` has no
`highPrice` field, so the chart cannot plot price-parked noise even by
accident. The chart draws `trendablePrice`, the market->mid->low fallback
the server already resolved, so the rule can't be re-derived wrong on the
client. Days with no usable price are dropped rather than plotted as zero -
a missing observation is not a price of $0.

**No charting library.** One line and two labels didn't justify a
dependency or the decision that comes with it; the chart is hand-rolled
SVG. Written down as a decision to revisit if charts get richer (multiple
series, brushing, touch tooltips), not as a permanent rule.

**A bottom-docked panel: tried, and reverted.** The detail pane sits in
normal flow beside the results, sticky, so the chart stays put while the
list scrolls. On a narrow window those columns stack, which put the chart
below fifty results - the owner reported that clicking a card appeared to
do nothing, and asked for the graph to open at the bottom of the *visible
page, not the bottom of the DOM*.

The fix built for that was a `position: fixed` panel docked to the
viewport bottom. It measured well - pinned correctly 3,724px down the
page, 29% of a 1280x820 desktop, 52% of a 375x812 phone, no horizontal
overflow - and the owner's verdict on seeing it was still "way worse."
Reverted to the sticky two-column layout. Worth recording because the
metrics were not the point: a panel permanently occupying a third of the
screen and covering the results is a worse way to browse than a column
that shares space with them, and no amount of correct geometry fixes
that. The underlying complaint (the chart being off-screen when the
columns stack on a narrow window) remains open, and wants a narrower fix
than replacing the layout wholesale.

**A process lesson that cost real work.** The revert was harder than it
should have been: the pre-dock layout had never been committed on its own,
because the whole front end was being held back over the unresolved
tcgplayer-CDN question. So "go back to how it was" meant reconstructing
the previous CSS and template from memory rather than `git revert`. The
owner's instruction afterwards was explicit - commit and push after every
major change, so recovering a previous state never depends on the agent
recalling code. An unrelated open question is not a reason to sit on
finished work; commit it and note the question instead.

**Two CSS bugs, one of which needed the browser to find.** Result tags
rendered as circles on any row with three of them. The cause was not the
obvious flex-shrink guess - inspecting computed styles showed the badge
carried `class="tag variants"`, which also matched the *variant tab
container's* `.variants { margin: 1rem 0 }`, inflating the flex line to
49.5px and stretching every 999px-radius pill on it into an ellipse. A
class-name collision, found only by measuring rather than by re-reading
the stylesheet. Separately, a `.chart-empty` rule sat in `search.css`
while the element it targeted lives in `price-chart`'s own template -
dead on arrival, since Angular scopes component styles. Moved to the
component that owns it.

**Thumbnails from TCGplayer's CDN: shipped, then removed.** The front end
went out with result thumbnails bound to `products.image_url`, which
points at `tcgplayer-cdn.tcgplayer.com` - about 50 browser requests to a
TCGplayer domain per search. This was flagged at the time and left for the
owner to decide, which was the wrong call twice over: the hard constraint
says "all outbound traffic goes to tcgcsv.com only" and "stop and ask
rather than finding a way," and the honest reading of that is not to ship
the traffic while the question is open. The owner's answer, when it came,
was unambiguous - never call TCGplayer - and the reasoning behind their
rule is worth restating plainly: their seller account is real income, the
project is speculative, and no feature is worth any amount of risk to it.

Removed entirely. Not deferred, not made lazy, not moved to a click - the
`<img>` is gone, `.thumb` styles are gone, and `imageUrl` is deliberately
absent from the client's `ItemSearchResult` interface so it cannot be
bound by accident. Verified by exercising a real search in the browser and
reading the performance timeline: 50 result rows, detail panel open, chart
rendered, and **zero** requests to any tcgplayer host - zero non-localhost
requests of any kind.

Two things this clarified in the rules themselves, now written into
CLAUDE.md. First, "only tcgcsv" means only tcgcsv: a CDN subdomain is
still TCGplayer, and the rule's intent is zero traffic to them, not zero
*crawling* of them. The distinction between scraping and hotlinking is
real but irrelevant to a rule written that way. Second, the argument that
tcgcsv publishes and documents the `imageUrl` field was treated as
evidence that using it was sanctioned - it is not. tcgcsv documenting what
a field contains says nothing about whether this project should send
traffic to the host it points at. Outbound *links* stay, and the reason is
a genuine distinction rather than a loophole: an `href` issues no request
until the owner clicks it themselves.

---

## Part 19 — Movers: the screen that needed the data checked first

**The ask, and why the defaults matter.** The owner specified it directly:
past week, top percentage downward, scoped to a whole game automatically,
with a set filter to narrow afterwards - and asked whether whole-game scope
was too heavy. It isn't: a full game compares in ~88ms unsmoothed, ~365ms
smoothed, against 41M rows.

**But the obvious query answers the wrong question twice over.** Both
problems only showed up by running it and reading the output, and both
would have put garbage at the top of the default view - the exact failure
mode from Part 12, where a technically correct number quietly answers
something other than what was asked.

*Percent change flatters cheap cards.* Unfiltered, the week's biggest
decliner was Shuckle, $0.06 -> $0.01, -83%. Four of the top twelve were
sub-$1 cards moving a few cents, and the genuinely interesting moves - a
$4,000 card at -75%, a $700 card at -73% - were buried among them. Fixed
with a minimum starting price, defaulting to $1.

*Thinly-traded cards lurch on single sales.* Checking the top result
before trusting it turned up something worse. Charizard Star (Delta
Species) sat at $4,000 for weeks, printed $980 for exactly one day,
snapped back to $4,000, then later settled near $990 - and its
`market_price` was sitting *below* its own `low_price` ($20,000) the whole
time, which is what an illiquid card looks like when the cheap copy sells
and only expensive listings remain. As a two-point comparison that one-day
blip reads as -75% one day and +308% the next. A decliners board built on
point-to-point would be topped by that kind of artifact every single day.

**The fix, and stating its limits honestly.** Compare the mean of the last
N days against the mean of N days a week earlier. Measured against that
exact card rather than assumed: the one-day artifact goes from -75.50% to
-25.17%, while the genuine sustained drop still reports -75.10%. So
averaging damps artifacts about 3x and leaves real moves at full
magnitude - it does not erase them. The first draft of the comment claimed
smoothing "discards" the blip; the measurement said otherwise and the
comment was corrected to match. Worth noting because the temptation with a
smoothing feature is to describe it as solving the problem rather than
reducing it.

**A 5x performance bug from where the dates were computed.** The first
version derived the window bounds in a CTE. `EXPLAIN (ANALYZE, BUFFERS)`
showed `temp read=775436` - roughly 6GB spilled to disk - because bounds
coming from a sub-select can't be folded into constants, so the planner
misused `idx_snapshots_date` and aggregated far more of the table than
needed. Resolving them to plain `DATE` variables in plpgsql first made it
an ordinary indexed range scan: 550ms -> 365ms. The remaining 365ms is
simply the cost of reading six days instead of two, and was left alone
rather than adding a multi-GB covering index that would slow every ingest
to speed up a query run a few times a day.

**Rows are per (product, variant), unlike search.** Not an inconsistency
with Part 16: a mover *is* one specific market's move, and the two
sub-types of a card move independently, so merging them here would be the
sub-type violation rather than the reverse. It also makes the movers
screen simpler than search - a row already names its exact variant, so
clicking it goes straight to a chart with no variant-picking step.

**A false alarm worth recording.** Testing the set filter through the DOM
showed zero rows while the identical API URL returned fifty. The instinct
was a binding bug; the cause was a 3-second wait in the test script that
the query occasionally outran. Re-reading the DOM a moment later showed
all fifty rows, correctly filtered. Distrust the test before the system -
the same lesson as the interleaved-curl parse error in Part 16.

---

## Part 20 — Card images, from a source that wants to be used

**The rule that made this necessary.** Images had already shipped once,
hotlinked from TCGplayer's CDN, and been removed (Part 18). The owner's
reaction was unambiguous, and the follow-up question was the right one:
where else can images come from, and is self-hosting them reasonable?
They then granted use of pokemontcg.io specifically - which is why
CLAUDE.md now carries an explicit permitted-hosts table rather than the
prose "only tcgcsv" that let a CDN slip through the first time.

**Self-hosting turned out to be the strictly better design, not a
compromise.** Fetch each image once, downscale it, serve it from our own
disk: the browser then contacts nobody. That is a stronger guarantee than
hotlinking a permitted host would give, and it makes the app work offline
for anything already cached. Their "small" PNG is ~157KB for something
rendered at 40x56px, so downscaling to a 160px-wide JPEG (~14KB) takes the
full catalog from ~2.6GB to roughly 200MB.

**Coverage was measured before committing to the work, and it is the
uncomfortable part.** Their catalog is 20,257 cards against our 27,807
Pokemon singles. Matching on name + card number + set total gives 58.9%,
plus 1.7% from an unambiguous name+number fallback - 60.5% overall, 73.3%
across main sets. Established sets do well (SV01 95%, Paradox Rift 94%),
but the newest sets run 43-53% because the upstream catalog lags releases,
and World Championship decks, jumbo cards, prize packs and promos are
absent entirely. That weakness lands exactly where a seller's attention
is - recent sets - so it was put to the owner as a decision rather than
buried. They chose to ship it with placeholders.

**Two upstream defects that shaped the implementation.** Their deep
pagination is broken: at pageSize=250, page 50 and beyond (offset ~12,250
of 20,479) return HTTP 500 every time, across six attempts - not
transient. Per-set queries are bounded and work, so the importer fetches
that way. Separately the API is simply flaky: one run had 11 of 174 sets
fail with 500/502 and succeed later. Retries with exponential backoff and
per-set isolation are requirements here, not defensive habits - the first
version aborted the entire run on one bad set.

**A correctness bug caught by thinking about what a partial failure
means.** Misses are recorded as `status='no_match'` so ~11,000 unmatchable
cards are not re-queried on every run. But if the catalog download is
incomplete, an unmatched product might simply live in a set that failed to
download - and writing `no_match` then is a permanent lie, because those
rows are never re-checked. A transient 500 would silently cost a card its
image forever. The importer now tracks whether the catalog is complete and
declines to record misses when it isn't, leaving those products for a
later run. The first real run exercised exactly this: 5 sets failed, 95 of
Base Set's 102 cards imported, and 7 unmatched products were correctly
left unrecorded.

**Loose matches are deliberately restricted to unambiguous ones.** Where
name+number alone hits several candidate cards, no image is chosen. For
someone pricing inventory a confidently wrong image is worse than an
obvious blank.

**A UI detail worth the extra state.** Relying on an `(error)` handler to
swap in a placeholder still let the browser paint its broken-image glyph
for a frame first - visible on roughly 40% of rows, which reads as a bug
rather than an absence. The image now starts transparent and fades in on
`load`, over a placeholder that is always present underneath.

---

## Part 21 — The `no_match` audit: a matcher bug wearing upstream absence as a disguise

Picked the project back up on 2026-08-17 (evening local / 2026-08-18 UTC)
with an image import already running. It finished mid-session. Its final
tally is where this starts:

| status | before this run | after |
|---|---|---|
| ok | 16,458 | 16,841 |
| no_match | 3,621 | **10,912** |
| failed | 54 | 54 |

That run resolved ~7,674 products and matched **383** of them. A ~95% miss
rate on the tail, against ~82% matched on everything before it.

**Why this had never been questioned.** The class javadoc on
`ImportCardImages` predicts it: *"Expect roughly 60% coverage, by
measurement,"* attributing the gap to the upstream catalog — *"World
Championship decks / jumbo / prize packs / promos are absent entirely."*
The final numbers land at 16,841 of 27,807 = **60.6%**, almost exactly on
the documented prediction. A measured expectation that a later run
reproduces to within half a percent is about as reassuring as a number
gets.

It was right about the total and wrong about the cause.

### Finding the real cause

The misses cluster in two shapes that "upstream is missing these" does not
explain. Skyridge — a 2003 set, fully catalogued upstream — sat at exactly
91 matched / 91 missed. Several 2025 sets sat near 50%. Those are not the
shapes of an absent set.

The names told the story:

```
ok        Abra                  no_match  Alakazam (2)
ok        Aerodactyl            no_match  Alakazam (H1)
ok        Charizard             no_match  Crobat (147)
```

Checked against the cached catalog offline — every one of those is
present upstream: `ecard3` carries `Alakazam` at `H1` and at `2`, `Crobat`
at `6`, `H5` and `147`. Same for the promos the javadoc called absent:
`swshp` has 304 cards, including the `Rillaboom V - SWSH014` we had just
written off.

**Two independent bugs, both in normalization.**

1. **`LEADING_DIGITS = ^\s*([0-9]+)` is anchored.** `numberDigits()`
   returns null for any card number that doesn't begin with a digit, and
   that null is fatal on *both* sides of the match — catalog cards are
   skipped at index time (`if (n == null || d == null) continue;`) and
   products never attempt a match. **1,550 of 20,479 catalog cards (7.6%)
   were never indexed at all**: every `H1`, `SV066`, `SWSH014`, `RC4`,
   `TG12`, `GG46`. An entire promo set can be in the cache and invisible
   to the matcher.

2. **`normName()` strips the wrong suffixes.** It removes a trailing
   `- 123` and a trailing `- 4/5`, but not a trailing parenthetical or a
   letter-number token. `Alakazam (H1)` normalizes to `alakazam h1` and
   `Rillaboom V - SWSH014` to `rillaboom v swsh014`; neither matches the
   catalog's bare `Alakazam` / `Rillaboom V`.

Bug 1 alone explains the 100%-miss promo sets. Bug 2 alone explains
Skyridge's clean 50/50 split. Together they explain the tail.

### Measuring it instead of asserting it

Both rules were replayed offline against `images/.catalog-cache` (20,479
cards) and all 10,912 `no_match` rows — no network, no re-import:

| rules | recovered from the 10,912 |
|---|---|
| current (baseline) | 0 |
| + number fix | 514 (4.7%) |
| + number fix + name fix | **8,834 (81.0%)** |

The number fix alone recovering only 4.7% is worth noting: the first
hypothesis was that bug 1 explained everything, and the data said it
explained a twentieth. The two bugs compound — most products needed both
fixes to match.

### The part that is not a bug, and not Claude's call

Of the 8,834, roughly half match a card in a **different set** from the
product's own:

- `Town Volunteers - 2004 (Kevin Nguyen)` → Aquapolis #136
- `Magikarp - 22/83 (Toys R Us Promo)` → Generations #22
- `Blastoise - 13/95 (Cracked Ice Holo)` → HS—Unleashed #13
- `Whimsicott (Poke Ball Pattern)` → the base Prismatic Evolutions print

Same artwork, genuinely the same card — but the championship stamp, the
Cracked Ice foil, the Poke Ball pattern and the jumbo format are precisely
what make these separate products at separate prices. Against this file's
own standing rule — *a confidently wrong image is worse than an obvious
blank for someone pricing inventory* — showing the base printing for a
variant is a domain judgment about how this app should behave, not an
implementation detail. It belongs to the owner, who knows these printing
conventions far better than Claude does.

(The same/cross split above is a set-name heuristic and **undercounts the
safe side**: the four promo groups, ~900 cards, are the same set under a
different name — `SWSH: Sword & Shield Promo Cards` vs `SWSH Black Star
Promos` — and are misfiled as cross-set. Don't quote 4,030/4,804 as
precise.)

### State at the end of this part

**Nothing was applied.** No code changed, and critically **no `no_match`
rows were deleted**. They are written to be permanent — never re-queried —
so any fix only takes effect after
`DELETE FROM card_images WHERE status='no_match'`, letting a later run
reconsider them. That is a destructive write against real data and was
left for the owner.

Open, in priority order:

1. The variant-image policy question above — it decides what the fixed
   matcher should *accept*, so it gates the code.
2. Fix both normalization bugs regardless; they are defects under any
   policy.
3. Correct the javadoc's "promos are absent entirely" claim, which is
   false and actively misleading — it is the sentence that made a 60%
   match rate look like a ceiling instead of a bug.

**The pattern worth keeping:** a documented, measured expectation
reproduced almost exactly, and it was still concealing a defect. The
measurement was real; the *explanation* attached to it had never been
tested. When a number matches its prediction, that confirms the number —
not the story about why.

---

## Part 22 — Saying which printing you are looking at

Part 21 left one question unanswered, and it was the owner's to answer: if
a variant printing shows the base card's artwork, is that acceptable? Their
answer reframed it — show the image, but **label it**.

That turns out to be the better shape for a reason worth recording. The
label is derived from the **product name**, not from the image match, so it
is correct for all 9,314 labelled Pokemon products *today* — whether or not
the matcher fix from Part 21 is ever applied, and whether or not a given
card has a picture at all. A decision that looked like it was blocking the
image work turned out not to depend on it.

### Where a variant is actually recorded

Nowhere structured. `extendedData` carries `Number` and `Rarity` as real
fields, but the printing qualifier exists only inside the product name:
`Blastoise - 13/95 (Cracked Ice Holo)` versus plain `Blastoise`. Those are
different products at different prices and the parenthetical is the only
thing that says so — the same reason `card_number` and `rarity` are treated
as identity rather than decoration (Part 17).

`variant_label()` extracts it. Three rules, each earned against real data
rather than guessed:

1. **A trailing `- YYYY (Person)` is a World Championship deck**, where the
   parenthetical is the deck's pilot, not a variant. Rendering it as-is
   would print a stranger's name on the card as though it described the
   printing. Verified before relying on it: all 1,947 Pokemon products
   matching that shape are in World Championship Decks and nothing else, so
   promoting the year to `2016 World Championship` is safe.
2. **A parenthetical that is really a card number is not a variant.**
   `Alakazam (2)`, `Crobat (147)`, `Latios EX (XY72)` all carry their
   number in the name. The reject shape is the one `item_search()` already
   uses to split a typed number off a query, so the two agree by
   construction.
3. **A trailing `[Staff]`-style token is its own variant** and is appended:
   `Prerelease · Staff`.

### Two bugs the data caught, both found by looking

Neither showed up in the unit-shaped checks — both appeared only when the
function was run against the real catalog.

**`Unown [A]`.** Rule 3 lifted any trailing bracket, so all 26 Unown cards
— cards whose bracket *is* their identity — got a one-letter "variant"
label. The fix was to make the bracket face the same validity test as the
parenthetical rather than a weaker one; that test now lives in
`is_variant_word()` and is called from both places, so the two can't drift
apart again. Caught by joining against `card_images` to find a labelled
product that actually had a picture to show — the entire result set was
Unown.

**The number after the qualifier.** TCGplayer sometimes appends the card
number *after* the bracket: `Professor's Research [Professor Magnolia] -
223`. That hid the bracket from a trailing match, leaving 45 products
unlabelled while their identical siblings were labelled. Fixed by dropping
a trailing number before extracting. The two shapes that must survive that
strip — a World Championship name and `Blastoise - 13/95 (Cracked Ice
Holo)` — both end in `)`, so neither matches it.

### Why the treatment differs by canvas

The list thumbnail is 40×56. Text rendered over it lands at roughly 5px:
not small, illegible — and it covers the artwork exactly where the eye is
scanning. So the two ends of the UI got different treatments:

- **List row:** a `--warn` corner wedge on the thumbnail, and the words as
  a *filled* pill beside the name. Filled, because `.tag.sealed` is already
  an outlined `--warn` pill and the two must not read as the same claim.
- **Detail panel:** the card image at 160×220 — which is exactly
  `ImportCardImages.THUMB_WIDTH`, so it renders 1:1 with no upscaling —
  with a semi-transparent band across the bottom. The band sits over the
  card's own text box, the least costly part of the art to cover, and
  truncates with the full label in `title` for the rare long ones
  (`Galarian Articuno, Galarian Zapdos, & Galarian Moltres`).

The detail panel had no image at all before this; it was title, subtitle
and chart. Adding one is what made a readable label possible.

The band's colours are literals rather than tokens, deliberately: it floats
on artwork, not on a themed surface, so it must hold in both themes without
following the theme.

### What this does not do

It does not apply the Part 21 matcher fix, and it does not clear a single
`no_match` row. Those are still the owner's call. What changed is that the
call is no longer risky in one direction: when cross-set images are turned
on, every variant that shows a base printing already says which printing it
is.

Movers deliberately doesn't carry the label yet — that screen shows no
images, so nothing there can mislead. It would still be useful as identity,
the same way `card_number` is, and that is a small follow-up rather than a
gap.

---

## Notes moved out of CLAUDE.md (2026-08-16 trim)

Kept here because they're reasoning worth having, but they were costing
tokens on every request while sitting in the auto-loaded file.

**Docker vs. a native Postgres install (flagged 2026-08-14, never
decided).** Docker was chosen for isolation and reproducibility - exact
version and schema checked into git via `docker-compose.yml`/`schema.sql`,
no manual install - and as a smoother path to eventual cloud hosting. But
on macOS a container runs inside a Linux VM, and that VM is the entire
reason a memory ceiling needs managing on a 16GB machine. A native
`brew install postgresql@16` removes that overhead completely: a normal
process, no VM, no `docker compose up -d` before running anything. The
cost is the reproducibility Docker gives. Since cloud hosting hadn't
started, the benefit wasn't being used. Not a one-way door either way -
`schema.sql` and `functions.sql` apply cleanly to both.

**The One Piece proof that generalization was real, not just designed
(2026-08-14).** `item_return()` was written and tested against Pokemon
only and never touched afterwards. Run against a One Piece product_id it
worked with zero code changes: Monkey.D.Luffy (001) Foil, $173.45 ->
$961.19, +454.16%. That single result is what distinguished "we built it
to generalize" from "it generalizes."

---

## Part 23 — The set overview, and the one card that was the whole set

The backlog had described this screen, twice, as the cheapest one left:
*"one endpoint, one table, no new SQL"* — `set_return()` and `set_value()`
were built and tested in Part 14 and simply never exposed. Both halves of
that description turned out to be wrong, and the second one mattered.

### It needed new SQL after all

`set_value()` and `set_return()` answer for **one** set. The screen wants
every set of a game at once, and there are 302 tracked groups — calling the
primitives per set is 604 round trips on a connection-per-request API. So
`game_set_overview()` computes both definitions set-wide in a single pass.

Its numbers are not *assumed* to match the primitives, they are checked
against them. The check is checked in, at
`sql/checks/set_overview_matches_primitives.sql`: it runs the bulk function,
then calls `set_value()` and `set_return()` for each of the top 20 sets by
value, and compares all six shared columns with `IS NOT DISTINCT FROM` so a
null matches a null rather than failing. All 20 sets agreed on all six
columns. Re-run it after any edit to either definition — it is the only thing
stopping the bulk path and the per-set path from quietly diverging, which
is the same failure mode the "handlers call SQL functions, not inline SQL"
rule exists to prevent.

Two performance notes carried over from `item_movers()`: the window bounds
are resolved into scalar `DATE` variables before the query rather than
computed in a CTE (from a sub-select the planner cannot fold them into
constants and aggregates far more of the 41M-row table than needed), and
`DISTINCT ON` finds each set's biggest contributor in one ordered pass
instead of a window function over every row. Every window — 7, 30, 90, 365
days — comes back in 0.19-0.31s, because the query is always a two-day
indexed scan regardless of how far apart the two days are.

### The finding: a set-level percentage is often one card

Sorting the first working version by value put **Base Set (Shadowless) at
+59.96% over 30 days**, on a basket of 202 cards. A 60% month for a 1999
set is not impossible, but it is a big enough claim to check, so it got
checked.

$9,750 of the $10,718 move was **one row** — Charizard, 1st Edition
Holofoil. Its history explains itself:

```
2026-07-12 .. 2026-07-22   market   250.00     low/mid/high all 100000.00
2026-07-23 .. 2026-08-03   market 10000.00     low/mid/high all 100000.00
2026-08-04 .. 2026-08-05   market   240.00     low/mid/high all 100000.00
2026-08-06 .. 2026-08-18   market 10000.00     low/mid/high all 100000.00
```

`market_price` toggles between ~$250 and exactly $10,000 for weeks at a
time, while low, mid and high sit parked at $100,000 — a textbook
price-parked listing of the kind that made the "never compute on
`high_price`" rule (Part 12), except here the *market* price is the one
oscillating. That is a thin market flipping regimes, not a 40x rally, and a
two-point return simply lands on whichever regime each chosen date fell in.

**Smoothing was tried first and does not fix it.** The obvious response was
to reuse `item_movers()`'s 3-day averaging at each end. Replayed against
this card, the smoothed values come back $250.00 and $10,000.00 —
completely unchanged, because the regimes run for weeks and a 3-day window
sits entirely inside one of them. Averaging only helps against single-day
spikes; this is not one.

**And it is not one weird card.** Measured across every Pokemon set with a
>=2% 30-day move, ranked by the biggest single row's share of gross
movement:

| set | basket | 30d | biggest single row | its share |
|---|---|---|---|---|
| Base Set (Shadowless) | 202 | +59.96% | Charizard 1st Ed Holo | 86.6% |
| Dark Explorers | 207 | +9.99% | Darkrai EX Full Art | 79.5% |
| Next Destinies | 190 | +10.60% | Zoroark Holofoil | 72.0% |
| Neo Destiny | 215 | -8.06% | Shining Charizard | 60.0% |
| Aquapolis | 325 | +20.35% | Lugia Holofoil | 59.4% |

In the shipped screen, **44 of 283 sets** have over half their movement
coming from a single card. That is 15%, not a rare edge case.

### What was done about it

Not a change to the metric. `set_return()`'s definition is an owner
decision (portfolio-style, singles only, both-days-present basket) and the
bulk function reproduces it exactly. What changed is what gets shown
*beside* it: every row names its biggest single contributor, that card's
dollar move, and its share of the set's gross movement, with a `--warn`
flag past 50% — the point where one card outweighs every other card in the
set combined. The column is sortable, so "which of these numbers should I
not trust" is one click.

The share is computed against **gross** movement (sum of absolute deltas),
not net. Net passes through zero: a set whose gains and losses nearly
cancel has a near-zero denominator, and ranking by share-of-net produced
meaningless four-digit percentages (`SWSH03: Darkness Ablaze`, whose net
30-day move is +0.01%, came out at 6100%). Gross is always >= |net| and
stays interpretable.

This is the same defect class as sealed product dominating set aggregates
(Parts 12 and 14) — but it cannot be fixed the same way. Sealed product was
excludable because it was a genuinely separate category; a Base Set
Charizard is a legitimate single that belongs in its set's total. The only
honest move left is disclosure.

### The other thing the screen has to say out loud

`total_value` and the return's `total_end` are different numbers and always
will be. Value covers every single priced on the end date; the return
covers only cards priced on **both** dates, so that a set gaining or losing
cards mid-window cannot masquerade as a price move. Legendary Collection
prices 217 cards today but only 213 have a July price, and that $1,259.85
difference would otherwise look like an arithmetic bug. The row shows
`+4 new` next to the count with the explanation in a tooltip.

### What this does not do

It does not smooth, cap, exclude, or otherwise touch the oscillating rows —
only labels their effect. Whether `market_price` readings like Charizard's
should be treated as data quality problems at ingest is a domain call and a
real open question (see "What's still open"). Nothing in this part changes
what is stored; `price_snapshots` remains append-only and unmodified.

---

## Part 24 — Prices that swing and come straight back

The owner's question, immediately after Part 23: *"is there any way to
intelligently disregard a price swing that seems obviously erroneous due to
immediate correction? I'm not saying every time something corrects to
smooth it out but if something swings -80% and then picks right back up at
its normal price then that is probably an error."*

The qualifier is the important half. This is not a request for smoothing —
it is a request to distinguish two things that a smoother treats
identically.

### The formulation that makes it tractable

Key on **persistence, not magnitude**. A genuine move leaves a *new level*
behind it; an error leaves the *same level* on both sides. So the test never
has to take a view on how large a real move is allowed to be — which is the
thing nobody can specify, and the reason a percentage threshold alone would
be arbitrary.

Concretely: a reading is suspect when its two neighbours agree with each
other and it disagrees with both.

### Median, not mean — and the mean is actively harmful

`item_movers()` already averages 3 days at each end. Replaying both filters
against Charizard Star (84198), a card carrying **both** defects — a real
step (970 → 4000 on 2026-07-24) and a one-day artifact ($980 on 2026-08-04
between $4,000 either side):

| date | raw | median-3 | mean-3 |
|---|---|---|---|
| 2026-07-23 | 950 | 970 | **1973** |
| 2026-07-24 | 4000 | **4000** | 2983 |
| 2026-08-04 | 980 | **4000** | 2993 |

Median-3 deletes the artifact outright and preserves the real step at full
size on the correct day. Mean-3 does worse than nothing: it leaves a
phantom −25% on 08-04 *and* fabricates a +108% move on 07-23 that never
happened, by smearing the genuine step across three days. A mean damps
everything, including the signal; a median annihilates single-sample
impulses and leaves steps and monotone trends untouched. That is a property
of the filter, not a tuning choice.

### Sizing it before building it

Over the 90 days to 2026-08-18, across 4.47M interior observations:

| distortion | spikes | total |
|---|---|---|
| under $1 | 4,652 | $138.51 |
| $1–10 | 22 | $68.34 |
| $10–100 | 11 | $614.64 |
| $100–1,000 | 8 | $3,849.52 |
| over $1,000 | 7 | $28,590.99 |

4,700 events, 0.11% of observations — but **15 of them carry 99.4% of the
money**. Heavy-tailed to the point that a filter tuned by *count* would
look pointless while a filter tuned by *dollars* is clearly worth having.
Hence the `p_despike_min_usd` gate at $10: it ignores 4,674 penny-card
events and catches 99.6% of the distortion.

The tail is all thin-market chase cards — Charizard 1st Ed, Pikachu Star,
Flareon Star, Shining Tyranitar, Boa Hancock. Same population as the
concentration problem in Part 23, which is not a coincidence: thin markets
produce both.

### What it actually changes

On the default 30-day window: **nothing**. The 48 spikes on the start date
totalled $0.81, all under the gate. That is the honest answer to "does this
matter today" and it is worth stating plainly rather than hiding behind the
feature existing.

But when a spike *does* land on an endpoint it is decisive. Running the
same query as of 2026-08-04, a date that held two large artifacts:

| set | raw | despiked | one substitution worth |
|---|---|---|---|
| EX Dragon Frontiers | **−20.91%** | **+8.43%** | $3,020 |
| EX Power Keepers | **−5.73%** | **+8.96%** | $1,250 |

Both verdicts reversed by a single replaced reading. EX Dragon Frontiers'
"driven by" card changed too — with the fake $3,020 move gone, a real card
is correctly identified as the biggest contributor, so the Part 23
concentration column reports on cleaned data rather than on the artifact.

### Two limits, both structural, both worth knowing

**1. Today can never be despiked.** The newest `snapshot_date` has no next
day, so the confirming evidence does not exist yet. The "now" endpoint of
every screen is exactly that date. This cleans history well and today not
at all; today's reading gets its check tomorrow. Not an implementation gap
— there is no way around it that doesn't involve predicting the future.

**2. A 3-window median only kills one-day impulses.** The Base Set
(Shadowless) Charizard from Part 23 dips to $240 on **both** 2026-08-04 and
08-05, so `median(10000, 240, 240) = 240` and it is correctly left alone.
Measured at the $10 gate over 90 days: **26 one-day runs caught, 20 two-day
runs missed.** A 5-window median would catch those too, at the cost of
erasing genuine two-day moves — a real trade-off, left as an open question
rather than decided quietly.

So despiking does *not* rescue the +59.96% Base Set headline. That number
came from comparing two multi-week regimes, and both persisted. The two
defects are related in cause and independent in treatment: Part 23
discloses concentration, Part 24 corrects impulses, and neither subsumes
the other.

### Why it is a visible toggle

The screen shows "Correct one-day price errors" as a checkbox, defaulted
on, and any set whose numbers were altered carries an `⤾ adjusted` marker
with a tooltip naming the count and dollar adjustment. A correction that
changes a reported percentage by 29 points and does not announce itself is
worse than either honest alternative. **A correction you cannot switch off
is a correction you cannot check.**

`price_snapshots` is untouched — append-only is not negotiable, and every
substitution happens at read time inside `game_set_overview()`.

`p_despike` is also why `sql/checks/set_overview_matches_primitives.sql`
now passes `FALSE`: `set_return()` is a raw two-point comparison, so with
despiking on the two legitimately disagree wherever a reading was replaced.
That is the feature working, not a regression, and the check keeps testing
the aggregation logic it exists to test.

---

### Addendum, same day — the mean in `item_movers()` was the bigger bug

Having established that a median beats a mean for despiking, the obvious
next question was whether `item_movers()`, which had smoothed with a **mean**
since Part 19, was wrong for the same reason. It was, and more consequentially
than the thing that prompted the investigation.

Two cards off the live board, both real moves, both misreported:

| card | shape | mean-3 | median-3 |
|---|---|---|---|
| Lillie - 125a/156 (199681) | $41.10 → $123.32, held 8 days | **+80.0%** | **+200.05%** |
| Flaaffy (85475) | $1.69 for 9 days → $50.83 | "now" = **$34.45** | "now" = **$50.83** |

Lillie's true move is +200%. The mean reported +80% because one day of the
post-step price leaked into the "then" window — the mean does not just damp
a move, it *contaminates one window with the other side of the step*.
Flaaffy is starker still: the mean reported a current price of $34.45, a
number that card has never traded at on any day.

Against the Part 19 worked example the asymmetry is exactly what was wanted:

| Charizard Star (84198) | mean-3 | median-3 |
|---|---|---|
| as of the one-day artifact | -25.17% | **0.00%** |
| as of a week later, drop real | -75.10% | **-75.03%** |

The artifact goes to zero — not damped, gone — while the genuine sustained
drop is untouched. Part 19's README text defended the mean's ~3x damping as
an acceptable price for suppressing artifacts. It was never a price that had
to be paid.

**On even window sizes.** `percentile_cont(0.5)` interpolates between the
two middle days when the window is even, which can report a price never
observed — precisely the failure the mean was replaced for. Odd sizes are
preferred and the default (3) is odd. Left as `percentile_cont` rather than
`percentile_disc` because disc biases low on even windows; neither is right
there, so the guidance is to keep the window odd.

**Cost, measured rather than assumed.** Isolating the aggregation, mean and
median cost the same (~80ms both). The all-games Movers query does take
~3.1-3.5s, but timing the pre-change function proves 3.14s of that predates
this work — the median adds ~13%, not the 7x a first glance suggested. That
pre-existing cost is worth its own look: the Movers screen defaults to all
games, so its default view is the slow path.

**Followed up the next day, and it was a bad plan rather than heavy work.**
All-games took ~3.6s while each game alone took ~0.4s — slower than the sum
of its parts, which is never a data-volume shape. The planner cannot
estimate selectivity through the CTEs and guessed **2 rows** out of the
prior/recent join against ~23,000 actual; on that estimate it chose nested
loops over `groups` (302 rows) and `categories`, materialised the real
23,000 rows and rescanned them once per group — `loops=302`, and
`temp read=889,352` blocks (~7GB) in the plan. Fixed by ranking and cutting
to the LIMIT in a `moves` CTE *before* joining the display tables, so
`groups`, `categories` and the `extended_data` unwrap never see more than
50 rows and the misestimate stops costing anything. **3.6s → 0.33s**, output
byte-identical in both directions. Worth keeping as a shape: a plan showing
a tiny estimate against a huge actual is usually fixed by shrinking the
input to the bad join, not by hinting the planner.

### And a question closed rather than left open

Part 24 left "should a 5-window median catch two-day runs?" open. Flaaffy
answers it: `median(1.69, 1.69, 1.69, 50.83, 50.83)` = **$1.69**. A 5-window
median would have erased a genuine two-day move completely and reported the
card as unmoved. Two-day steps are clearly common enough that this is not a
tolerable trade for the 20 two-day artifacts it would also catch.
**Decided against**, on evidence rather than caution.

---

## Part 25 — The label that shouted the card's own name

The owner, unprompted: *"i really don't like how the labels are for the
images that were variants."* A taste complaint, which turned out to have a
measurable cause and to point at a feature guarding nothing.

### Two things wearing one style

Part 22 built variant labels to answer a real question from Part 21: if a
variant printing displays the *base* card's artwork, say which printing this
actually is. That is a **caution**, and it was styled like one — a `--warn`
corner wedge on the thumbnail, a filled `--warn` pill in the row, a band
across the detail image.

But the label was applied to every labelled product, and most labelled
products are not that case at all. They are **identity**: `Professor's
Research [Professor Magnolia]`, `Ancient Technical Machine [Ice]`,
`Burmy (Plant Cloak)`. Caution's styling on identity's population, which is
why every row looked like a problem.

### The measurement that explained the irritation

`variant_label()` derives the label **from the product name**. So the pill
usually repeats words already on screen a couple of inches to its left:

| labelled products | label already in the name |
|---|---|
| 12,200 | 9,607 (**78.7%**) |

The loudest element in the row was, most of the time, a duplicate of the
row's own title — set in a heavier weight than the title itself.

The other ~21% earn their place by reformatting rather than repeating:
`Night Stretcher - 2025 (Riley McKay)` → `2025 World Championship`,
`Cleffa - 037 (Prerelease) [Staff]` → `Prerelease · Staff`.

### The finding that reframed the fix

The obvious move was to restyle the pill. The better question was how often
the caution is *warranted*, and the answer is essentially never:

| cached images | belonging to a labelled product |
|---|---|
| 16,841 | **16** |

Sixteen. And inspecting all sixteen, **not one is a borrowed image** —
`[Ice]`, `[Rock]`, `[Steel]`, `[Ghetsis]`, `(Plant Cloak)` and the
`[Professor X]` cards are genuinely different cards whose artwork is
correct. Every one matched under the strictest rule, `name+number+total`.

There is a neat reason for that overlap being so small, and it is the Part
21 bug: `normName()` doesn't strip trailing parentheticals, so variant
products are *precisely* the ones that fail to match an image. The warning
system was guarding a population of zero, and could only acquire one if the
Part 21 fix were applied.

### What shipped

The label is shown only when it isn't already in the name, and rendered as
an ordinary `--line`/`--muted` chip beside card number and rarity. The
corner wedge is gone. The detail band follows the same rule, so it too is
effectively gone until a genuine borrowed image exists.

Verified against the live app: searching `Professor's Research` drops from
50 amber pills to 7 quiet chips, and all 7 are World Championship decks —
exactly the reformatting case. `Ancient Technical Machine` keeps 4 chips,
all of them World Championship decks, while the plain `[Ice]`/`[Rock]`/
`[Steel]` rows carry none.

There is a small joke in the detail panel that makes the point: the
`[Ice]` card's own printed artwork reads "Ancient Technical Machine [Ice]".
The band was covering the card's art in order to repeat what the heading
above it *and* the picture underneath it both already said.

### The part deliberately left in place

`--warn` is now reserved for a genuine borrowed image rather than retired.
Four treatments were mocked up and compared side by side before choosing;
the one chosen ("only when it adds something") renders identically today to
the more ambitious version that also handles borrowed art, because there
are no borrowed images to handle. So nothing was lost by taking the simpler
one first.

When the Part 21 matcher fix lands, the caution should return for exactly
those rows — and the **matcher** should record that it borrowed, rather than
the UI inferring it by comparing set names. Part 21 already established that
the set-name comparison is a heuristic that undercounts.

**The pattern worth keeping:** a styling complaint is worth taking as far as
"what population does this styling actually serve?" The answer here was
zero, and no amount of restyling would have found that.

---

## Part 26 — Fixing the matcher, and refusing to guess the verdict

Part 21 audited the `no_match` rows and found two normalization defects
worth 8,834 recoverable images, then stopped: applying them meant deciding
whether a variant may display its base printing's artwork, and that was the
owner's call. Part 22 answered the display half ("show it, but label it").
This part applies the fix, and finds that the *hard* half of the question
was never really about display at all.

### The two defects, and a third thing that was needed

1. **`LEADING_DIGITS` was anchored** (`^\s*([0-9]+)`), so `numberDigits()`
   returned null for any number not starting with a digit — `H1`, `SV066`,
   `SWSH014`, `TG12`, `GG46`. That null was fatal on *both* sides: 1,550 of
   20,479 catalog cards never entered the index, and the matching products
   never attempted a lookup. Now unanchored, with leading zeros stripped by
   string rather than `parseInt` (a long digit run would overflow an int and
   the value is never needed numerically).
2. **`normName()` left trailing letter-number tokens attached**, so
   `Rillaboom V - SWSH014` never met the catalog's bare `Rillaboom V`. The
   trailing-token rule was widened from digits-only to allow a letter prefix.
3. **A `--dry-run` mode**, which was not in the plan and earned its place
   immediately. It matches and reports without downloading, writing, or
   recording a single `no_match`. That mattered because the misses this
   importer records are *permanent* — they are never re-queried — so a rule
   that is wrong writes a lie that no later run corrects.

Measured before anything was written: **9,386 of 10,966 checked (85.6%)**,
against Part 21's replayed prediction of 8,834.

### Two tiers, and why the tier is the answer

The owner's instruction was to record cross-printing **at match time**
rather than infer it later by comparing set names — Part 21 had already
shown that comparison is unreliable, because the two catalogs disagree
(`SWSH: Sword & Shield Promo Cards` is pokemontcg.io's `SWSH Black Star
Promos`, ~900 cards a name comparison would wrongly call borrowed).

Inspecting the cached catalog showed why that instruction was right, and
gave a cleaner signal than expected: **the catalog carries the printing
qualifier in the card's own name** when the qualifier is real identity.
`swsh35-62` is `Professor's Research (Professor Magnolia)`; `ex5-84` is
`Ancient Technical Machine [Ice]`. So:

- **Tier 1** matches on the product's name with the qualifier intact. When
  the catalog has that card, this resolves to its actual artwork.
- **Tier 2** retries with the qualifier stripped. Reaching tier 2 *means*
  the catalog holds no card bearing this printing.

Which tier succeeded is the entire signal. No set names are compared
anywhere in the importer.

### The dry run catching a defect on its first outing

The first version of the tier-2 test flagged 8,056 matches as base-art,
including `Aerodactyl (1) → Fossil #1`. But `(1)` is a *card number*, not a
printing qualifier, and that match is perfectly correct.

Part 22 had already solved exactly this problem in SQL — `is_variant_word()`
rejects a parenthetical that is really a card number, and carries a length
floor so all 26 `Unown [A]`..`[Z]` do not read as variants. Rather than
write a second rule that could drift from it, it was ported to Java
verbatim. The rule deciding a row's *label* and the rule deciding its
*image* are now the same rule.

That took base-art matches from 8,056 to 6,827.

### Where a mechanical answer ran out

6,827 was still visibly too many, and inspection showed why — tier 2 fires
on two genuinely different situations:

| product | catalog match | is the picture wrong? |
|---|---|---|
| `Blastoise - 13/95 (Cracked Ice Holo)` | plain `Blastoise` | **yes** — foil treatment over base art |
| `Aerodactyl (Delta Species)` | `Aerodactyl δ` | **no** — same card, spelled differently |
| `Absol (Team Plasma)` | `Absol`, Plasma Freeze #67 | **no** — same card |
| `Altaria - BW48 (Prerelease)` | plain `Altaria` BW48 | **yes** — prerelease stamp |

"Did the printed set total agree?" looked like a discriminator and splits
cleanly 6,097 / 730 — but it fails on the headline case. `Gliscor - 4/90
(Cracked Ice Holo)` carries number `004/090` and the catalog's Gliscor is #4
of 90, because a foil reprint keeps the original's numbering. Total agrees;
the artwork is still the base printing.

So there is no mechanical answer. Distinguishing "Cracked Ice Holo is a foil
treatment" from "Delta Species is card identity" is *vocabulary knowledge* —
exactly the domain judgment Part 21 reserved for the owner.

### Storing the fact instead of the verdict

`card_images.stripped_qualifier` records **the word that had to be dropped**,
not a boolean verdict. That was the change that resolved the impasse: the
classification can then be made once, in SQL, and applied retroactively to
every row without re-running an 80-minute import. Early distribution:

```
delta species 177 | cosmos holo 118 | team plasma 99 | full art 39
prime 28 | cracked ice holo 27 | shiny 24 | staff 20 | prerelease 16
```

Nothing is surfaced in the UI. Part 25 had just established that `--warn`
should not be spent until something earns it, and a caution shown on
`delta species` would be exactly the false alarm that argument was about.

### The result

The full run finished 2026-08-19: **26,166 images, up from 16,841** —
Pokemon singles coverage **60.6% → 94.1%**. The DB count and the files on
disk agree exactly (26,166 / 372MB), which is the check worth doing, since
the two are written by different code paths.

| | before | after |
|---|---|---|
| `ok` | 16,841 | **26,166** |
| `no_match` | 10,912 | 1,580 |
| `failed` | 54 | 61 |

Of the matches, **19,346 resolved on the product's own name** and **6,820
needed the qualifier stripped** — the latter being the population whose
`stripped_qualifier` still awaits classification. The 61 `failed` rows are
retryable and a later run picks them up.

The class javadoc's old "expect roughly 60% coverage, by measurement"
prediction is now decisively wrong, which was the point of Part 21: that
number was a bug reproducing itself, not a ceiling.

### Rollout, and two things that broke

Validated on group 2289 (Blister Exclusives, 140 rows) before anything
wider — that set is where the Cracked Ice Holo cards live, so it exercises
the contentious case directly. 140/140 matched and downloaded; spot-checking
Zoroark 71/114 showed the correct card in its *normal* printing, which is
precisely what `stripped_qualifier = 'cracked ice holo'` claims.

Then `DELETE FROM card_images WHERE status='no_match'` (10,912 rows, none
carrying a `file_path`, so no image file was destroyed) and a full run.

Two failures worth recording:

- **The jar trap, applied to only one process.** `mvn package` was run while
  the API server was live off the same jar. The import was protected; the
  API was forgotten. Java loads classes lazily, so it kept serving until it
  needed `QueryRepository$GameSet`, then threw `ClassNotFoundException` and
  500'd the Sets screen. The rule in CLAUDE.md said "a running import or
  backfill" — it is *any* process running from that jar, and the API is
  always one of them.
- **`CREATE OR REPLACE` does not replace across signatures.** Adding the
  despike parameters to `game_set_overview()` created a second *overload*
  and left the 3-argument version behind, making `game_set_overview(NULL,
  30)` ambiguous and unresolvable. `item_movers()` already carried a
  `DROP FUNCTION IF EXISTS` guard for this exact reason; the same guard was
  added.

**The pattern worth keeping:** the useful move was not solving the hard
question but *changing what gets stored* so the question could be answered
later, cheaply, by the person who can answer it. A boolean would have forced
a guess into 6,827 rows; a string left the decision open at no cost.

**Superseded in part by Part 27.** Storing the fact was the right call and
still is. The conclusion attached to it — that no mechanical test could
decide the verdict — was drawn from one candidate test ("did the printed
total agree?") and did not hold. Asking which *other* products claim the
same catalog card resolves 91% of these rows. Read Part 27 before acting on
anything below about the vocabulary needing classification.

---

## Part 27 — The test Part 26 said did not exist

Part 26 ended by refusing to guess. It recorded `stripped_qualifier` — the
printing qualifier dropped before a product matched — as a *fact* rather
than a verdict, because it could not mechanically tell these apart:

| product | catalog match | is the picture wrong? |
|---|---|---|
| `Blastoise - 13/95 (Cracked Ice Holo)` | plain `Blastoise` | **yes** — foil over base art |
| `Aerodactyl (Delta Species)` | `Aerodactyl δ` | **no** — same card, spelled differently |

That refusal was right, and the reasoning behind it was sound. The
conclusion drawn from it was too strong.

### What Part 26 actually tested

It tested **"did the printed set total agree?"** and rejected it, correctly:
`Gliscor - 4/90 (Cracked Ice Holo)` carries number `004/090`, the catalog's
Gliscor is #4 of 90, the totals agree, and the artwork is still the base
printing. A foil reprint keeps the original's numbering, so the printed
total can never distinguish a reprint from the thing it reprints.

From that failure Part 26 generalized to "there is no mechanical answer."
But the total is a property of the *product's name*. It was never the only
observable available.

### The observable it missed

**One pokemontcg.io card is one picture.** When several of our products
resolve to the same `source_id`, they are all displaying that one picture,
whatever their names promise. So the question is not "what does this
qualifier mean" but **"who else claims this card?"** — and unlike the
vocabulary question, that one is answerable from data we already have.

Checked on disk before any code was written, because a claim this
convenient deserved to be falsified first:

```
images/3/83895.jpg   Blastoise (Unleashed 13/95)            4cc2be6f23a5...
images/3/145612.jpg  Blastoise - 13/95 (Cracked Ice Holo)   4cc2be6f23a5...
images/3/211517.jpg  Blastoise - 13/95 (Prerelease)         4cc2be6f23a5...
```

Three products, three files, one picture — byte-identical. Against that,
`Acerola` (`sm3-112`) and `Acerola (Full Art)` (`sm3-142`) hash differently
and, opened up, are visibly different cards: standard bordered art against
full-bleed. The Full Art flag was a false alarm, and 535 of its 536 rows
are the same story.

### Three verdicts, and why the third is not a failure

`card_image_provenance()` classifies by group rather than by row:

- **`own_art`** — sole claimant of its catalog card, or the unqualified
  claimant of a shared one.
- **`base_art`** — qualified, and an unqualified product claims the same
  card. That product is named as `owner_product_id`.
- **`unknown`** — shared, but *every* claimant is qualified.

| verdict | cards | of which flagged |
|---|---|---|
| `own_art` | 21,096 | 1,750 |
| `base_art` | 4,462 | 4,462 |
| `unknown` | 608 | 608 |

**91% of the 6,820 flagged rows resolve without a domain call.** The 1,750
own-art rows mean `stripped_qualifier` was overstating the problem by about
3.5x — it flags every card that *needed* a qualifier stripped, which is not
the same population as the cards *showing someone else's art*.

The 608 are the honest residue, and they are the case Part 26 was actually
right about. `ex15-28` is claimed by `Dragonair (Delta Species)` and
`Dragonair (Delta Species) - 28/101 (Prerelease)`, and by nothing else — no
plain listing exists to anchor them. Deciding which owns the art requires
knowing that *delta species* is card identity while *prerelease* is a stamp
over base art. That is knowledge about Pokemon printings, not about our
data, and it stays the owner's call — over 238 image groups now, rather
than a 754-word vocabulary.

### A correction made mid-investigation

An earlier version of this test compared *names*: a row borrowed if a
shorter product name was a prefix of its own. It scored well and it was
wrong. `Basic Darkness Energy (Cracked Ice Holo)` shares `sve-15` with
`Basic Darkness Energy - 015`, which is not a prefix of it — the base
carries a card number exactly where the borrower carries a qualifier. And
`Blastoise - 13/95 (Cracked Ice Holo)` was scored *own art* on the reasoning
that no plain `Blastoise - 13/95` existed; the plain listing does exist, as
`Blastoise` in Unleashed, and only a query narrowed to the wrong `source_id`
prefix hid it. Checking the actual files is what caught both.

The rule that survived does not look at names at all.

### Why it computes instead of storing

No verdict column was added. The classification depends on which *other*
products currently hold images, so a later import that finally matches a
missing plain printing silently invalidates a stored value — the same
staleness argument that keeps all-time-high computed on demand rather than
maintained incrementally (CLAUDE.md, Data invariants). Recomputing is always
correct; the input it reads is already stored.

Eight invariants guard the definition in
`sql/checks/card_image_provenance_invariants.sql`: every image classified
exactly once, `base_art` always naming an owner that is not itself, an
unqualified row never `base_art`, `unknown` groups never containing a plain
listing, sole claimants always `own_art`. All pass.

### Nothing shown in the UI yet

Deliberately, and consistent with Part 25: `--warn` is not spent until
something earns it. `base_art` is now a defensible thing to surface — it is
measured rather than guessed, and it names the card whose picture is being
shown — but whether a borrowed picture is even worth flagging to someone
pricing inventory is a product question, not a data one. The function exists;
what reads it is the next decision.

### Addendum, same day — the blind spot the owner found in one sentence

The verdicts above were shown to the owner, who replied with two facts:
*Delta Species was an attribute some cards had during those sets*, and
*Prerelease almost always has a stamp*.

The first confirmed what the structural test already said. The second broke
it.

`Bronzong - SWSH091 (Prerelease)` is a **sole claimant** — nothing else in
our catalog wants `swshp-SWSH091` — so it was classified `own_art`. Opening
the file shows the ordinary Battle Styles promo, no prerelease stamp
anywhere on it. The verdict was wrong, and wrong for a reason worth stating
plainly:

> Sole-claimancy proves that no *other* product took the picture. It never
> proves the picture matches the physical card.

Where a stamped printing has no unstamped sibling listed on TCGplayer,
there is nothing for the structural test to notice, and it returns a
confident `own_art`. Eighteen prerelease cards had that false all-clear.

The same structural fact reads oppositely depending on the word: Delta
Species has **159** sole claimants and every one is genuinely correct,
because the catalog lists those cards in their own right as `Aerodactyl δ`.
Identical evidence, opposite verdicts, and only Pokemon printing knowledge
separates them. Part 26's instinct — that this specific question belongs to
the owner — was right; it was only the scope that was overstated.

### The vocabulary as data

`image_qualifier_class` classifies a qualifier as `identity` (the catalog
holds this card in its own right) or `treatment` (a stamp or foil over a
base printing the catalog holds unstamped). A treatment is `base_art`
regardless of claimancy; an identity claimant can *own* a shared card, which
also gives the borrower an owner to name — `ex15-28` now resolves, with
`Dragonair (Delta Species)` holding the art and its `(Prerelease)` reprint
borrowing it.

It is a table rather than a `CASE`, for the reason `tracked_categories` is:
adding a classification is an INSERT the owner can make without a code
change, and an unclassified qualifier falls back to the structural test
rather than being guessed at. The owner's hedge is preserved verbatim in the
`note` column — "almost always" is not flattened into "always."

A hundred and twenty-nine classifications moved 453 cards out of `unknown`
and corrected **47**:

| verdict | before | after |
|---|---|---|
| `own_art` | 21,096 | **21,113** |
| `base_art` | 4,462 | **4,898** |
| `unknown` | 608 | **155** |

`own_art` landing back on its starting number is a coincidence, not a
no-op: 45 cards left it as false all-clears were corrected, and 45 arrived
from `unknown` once `delta species` and `team plasma` were confirmed as
identity.

`staff` was the third, added the moment the owner confirmed it "is just like
PreRelease" - one INSERT, 121 cards, no code touched and no re-import. That
is the design working: the mechanism was built once, and the expensive part
(knowing what the words mean) stays where it belongs.

`team plasma` was the fourth - card identity, not a stamp (owner,
2026-08-19), which sent its 28 `unknown` rows to `own_art` and confirmed the
70 sole claimants that were already there.

One known imprecision, left alone deliberately at 3 rows: an `identity`
qualifier sharing with an unqualified claimant is still called `base_art`,
but `Kyurem (Team Plasma)` and `Kyurem - 31/116` are two TCGplayer listings
of one physical card, so neither is really borrowing. Tightening the rule
for that case is not worth the complexity at this size; revisit if a future
classification makes the population meaningful.

`cosmos holo` (a foil type) and `winner` (a stamp) followed, both
treatments, and both repeated the prerelease lesson at a larger scale: 27
more sole claimants had been given false all-clears, 25 of them cosmos
holo. That is now **45 corrections from six words** - each one a card the
structural test was confident about and wrong about, and none of them
findable without knowing what the word meant.

`finalist` and `semi-finalist` followed, and classifying them surfaced how
large the family behind them was: 44 further qualifiers are tournament and
placement stamps of the same kind — `1st place` through `4th place`,
`city`/`state`/`regional`/`national championships`, `world championships
2017`..`2024`, `champion`, and year-prefixed forms like `2015 semi-finalist`.
The owner classified the family in one call: *"As far as I know these are
all in that category."*

**The family was applied as an explicit list, not as a pattern.** The regex
`/finalist|champion|^[0-9]+(st|nd|rd|th) place$/` was used to *find* the 44
candidates, all of them reviewed, and then the qualifiers themselves were
written into the seed. A pattern evaluated at runtime would silently
classify whatever future qualifier happened to match it — which is exactly
the "a wrong rule writes a permanent lie" hazard the importer's `--dry-run`
exists for (Part 26). A new qualifier arriving later is deliberately left
`unknown` until someone looks at it.

It also exposed spelling near-duplicates — `quarter finalist` alongside
`quarter-finalist`, `semi finalist` alongside `semi-finalist` — and chasing
those down landed somewhere other than where it was first aimed.

The obvious fix was to normalise in `ImportCardImages.strippedQualifier()`,
at the source. Looking at it, that would have been wrong: the extractor is
faithfully recording what the product name says, and TCGplayer really does
sell both a `Semi-Finalist` and a `Semi Finalist` printing. Normalising
there would destroy a true fact about the catalog to save a row in a lookup
table — the same instinct Part 26 resisted when it stored the stripped word
instead of a verdict.

So the normalisation went where the comparison happens instead.
`normalize_qualifier()` lowercases, turns hyphens and underscores into
spaces and collapses runs of space; `card_image_provenance()` joins the
vocabulary through it. The raw record keeps both spellings, and the
vocabulary needs one row per concept — 52 rows down to 50, with **every
verdict unchanged**, which is the check worth doing since a normalisation
that moves a verdict is a normalisation doing more than it claims.

It is deliberately conservative: no stemming, no word-dropping, so
`regional championship` and `regional championships` stay separate rows
until someone says they shouldn't be. And it introduces a structural
hazard that gets its own invariant — the vocabulary must store already
normalised qualifiers, because two rows differing only by a hyphen would
both match one image and duplicate it in the output.

The last coherent block was World Championship Decks — 86 unresolved cards
across 77 player names. The evidence was suggestive but not conclusive:
`Articuno - 2015 (Jacob Van Wagner)` resolves to the plain XY6 Roaring
Skies Articuno, no championship stamp, no name on the card. Suggestive is
not the same as known, and the entries in `image_qualifier_class` are
recorded as the owner's calls in their own words, so writing an inference
in under their name would have been a different act from them making it.
They checked and answered: *"a distinctive special edition of that card,
essentially the same type of thing as a pre release card."*

**Selected structurally, not by pattern.** A qualifier joined the family
only if *every* card carrying it sits in the World Championship Decks
group. No string rule reliably recognises a person's name, and matching on
the shape of a string is not the same as knowing what the card is — the
same distinction that made "did the printed total agree?" fail in Part 26.
The set membership is a fact about the product; a name-shaped regex would
have been a guess wearing a rule's clothing.

That resolved 86 and corrected one more sole claimant, bringing the running
total to **47 cards the structural test was confident about and wrong
about**.

`prime` and `special` came last, and both were `identity` — "prime is part
of the label, an attribute; special is saying it is a nicer version or
alternate than the base" (owner, 2026-08-19). These were the first
classifications where the stakes sat on the *already-resolved* rows rather
than the unknown ones: 21 `prime` and 11 `special` cards were sole
claimants reading `own_art`, so a `treatment` verdict would have condemned
32 correct pictures, while `identity` merely confirmed them and resolved 19
more.

Because the reading of "alternate than the base" could have gone either
way, both were checked on disk before being written: `Gengar (Prime)`
resolves to `hgss4-94`, which carries the Prime frame, and `Darkness Energy
(Special)` resolves to `col1-86`, which is labelled **"Special Energy
Card"** on the card face. Both are their own cards, not finishes over a
base printing. All 51 rows now read `own_art`.

What remains is 155 cards across ~85 qualifiers — a genuine scatter of a
few cards each across Deck Exclusives, Nintendo Promos and assorted promo
sets. No block left is large enough to distort a screen.

**The second pattern worth keeping:** a test validated against 26,166 rows
and eight passing invariants was still wrong about a category no query
could have surfaced, because the evidence that would have contradicted it
does not exist in the data. It took one sentence from someone who knows the
cards. Cross-checking a derived signal against the domain is not a
formality after the numbers look good — the numbers looked good.

**The pattern worth keeping:** "no mechanical test exists" is a claim about
the observables someone thought to try, not about the problem. Part 26
tested one property of the product name, found it insufficient, and
generalized. The question it was really asking — *is this picture this
card's own?* — was answerable all along by looking at the picture, which
nothing in that part ever did.

---

## Part 28 — Corrections that stopped at the docs

A cleanup pass, opened with no particular target: read the state, run the
checks, fix what is actually wrong. The checks were green — all eleven
provenance invariants `t`, tree clean, data current to 2026-08-20. What was
wrong was not in the data.

### A retraction that never reached the file it was about

Part 27's whole subject was overturning Part 26's conclusion that whether a
stripped qualifier means the picture is wrong "has no mechanical answer."
Commit `ec994e2` did that carefully: it corrected CLAUDE.md, added the new
reasoning to this document, and deliberately left Part 26's own narrative
intact with a forward pointer, because a history that edits itself is not a
history.

It changed no source files. `git show --stat` says so plainly: two files,
both Markdown.

So the claim survived, in `ImportCardImages`' class javadoc:

> Whether a stripped qualifier means the picture is wrong has no mechanical
> answer [...] so the word is stored and the classification left to be made
> once, in SQL, retroactively.

That is the file someone opens *first* when they next touch qualifier
handling — it is where `strippedQualifier()` lives. The retraction reached
the two documents a reader consults deliberately, and missed the one they
arrive at by following the code. A future session would have read a
confident statement that no test exists, in the very class whose output the
test consumes, and had no reason to doubt it.

The failure mode is the one CLAUDE.md's "Before you cite a rule here"
section already describes — a claim hardening through repetition until it
gets quoted back as settled — with a twist worth naming: **it had already
been retracted, and the retraction simply did not reach far enough.** The
fix was not new reasoning. It was carrying `ec994e2`'s conclusion the last
few feet, plus a forward pointer to `card_image_provenance()` so the next
reader lands on the answer rather than the impasse.

Two smaller things went in with it. The javadoc led with **"Coverage runs
~60% — but that number is NOT a ceiling, it is a bug"** in bold present
tense, and only twenty lines later noted that both defects were fixed and
coverage is 94.1%. And CLAUDE.md's rule for *recording* the stripped word
verbatim — never normalising it in Java, because TCGplayer really does sell
both `Semi-Finalist` and `Semi Finalist` — was documented only in CLAUDE.md,
not in the class that would have to be edited to break it. A rule is better
placed where the tempting edit happens.

### A count that drifted inside its own session

CLAUDE.md said the provenance invariants were ten and that "all ten must
read `t`." The check returns eleven; `vocabulary_is_stored_normalised`
arrived with the normalisation work in `98622da`, the same session that
wrote the sentence.

The interesting part is the choice of fix. Correcting ten to eleven restores
the truth and rebuilds the same trap for whoever adds a twelfth. A few
hundred lines up, the sibling check is described as "every column must read
`t`" — shape rather than count, and it has never gone stale because there is
nothing in it to go stale. That phrasing was adopted here.

### The working directory that failed in both directions

The last item was a real defect, and the only one in this pass that was not
about words.

Both `ImportCardImages` and `ApiServer` resolved
`Env.get("CARDTRACKER_IMAGE_DIR", "images")` — a **relative** path, meaning
a different directory for every place a process is launched from. The
backlog had carried it for days ("Either resolve relative to the jar, or
refuse to start when the target directory isn't the expected one") after it
cost a day once already.

What makes it worth more than its size is that neither end reports anything.
The importer called `Files.createDirectories()` on whatever the working
directory implied, so a wrong launch *manufactured* a plausible empty tree
and downloaded into it — long, busy, apparently healthy. The API, launched
wrong, resolved to a directory that does not exist and returned 404 per
image, which the front end renders as the placeholder it already shows for
the 1,580 genuine `no_match` rows. A misconfiguration and a known upstream
gap look identical on screen.

`ImageDir.resolve()` is now the single resolver for both. Of the backlog's
two options, resolving against the jar was rejected: the jar lives in
`target/`, which `mvn package` is free to wipe, so that trades a silent
split for a silent deletion. It refuses instead, detecting the project root
by `pom.xml` **and** `sql/schema.sql` — `pom.xml` alone is satisfied by any
Maven checkout on the machine — and the error names the working directory it
found and the two ways out.

An explicit `CARDTRACKER_IMAGE_DIR` is honoured with no check at all. A path
someone set is a decision; only the default can be an accident.

Verified against `target/classes` rather than by reading it, on all three
paths: from the project root it resolves to `<root>/images`; from an
unrelated directory it throws with the cwd and the fix named, and
**creates nothing** — the refusal happens before `createDirectories()`, which
is the specific behaviour that used to leave a decoy tree behind; and with an
absolute override set it resolves to that path from that same wrong
directory. The API also now prints its image root at startup, because a
wrong root and an absent image are otherwise the same placeholder.

**The pattern worth keeping:** a correction is not finished when the
document is right. It is finished when every place that repeats the old
claim has been found — and source comments are where claims go to survive,
because nothing re-reads them on purpose. Part 27 established that "no
mechanical test exists" is a statement about what someone thought to try;
this part is the same shape one level up. `git show --stat` on the commit
that made a correction is a cheap way to ask whether it reached far enough.

---

## Part 29 — Spending the reserved warning

`card_image_provenance()` had been correct and unread since Part 27: eleven
invariants passing, 21,113 `own_art` / 4,898 `base_art` / 155 `unknown`, and
not one caller outside SQL. This part connected it.

### The reservation it was built to satisfy

Part 25 removed an amber corner wedge from the search rows. The wedge marked
every product carrying a variant label — 12,200 of them — on the theory that a
labelled printing might be showing another card's picture. It was pulled
because at the time **nothing could tell which ones actually were**, so it
warned uniformly about a condition it could not detect. What survived was a
note in `search.css` reserving `--warn` for "a picture that is genuinely
another printing's art - which nothing currently qualifies for."

That sentence was a promise with a precondition, and Part 27 met it. 4,898
rows now qualify by name.

### Only one of the three verdicts is a warning

`base_art` gets the band. The other two deliberately get nothing:

* `own_art` is the picture being right. There is nothing to say.
* `unknown` is an **open question, not a suspicion.** Marking it would
  recreate the exact Part 25 error one category over — decorating rows
  because something is undetermined rather than because something is known.
  The 155 stay silent until the vocabulary settles them.

### Where it went, and where it deliberately did not

The band renders in the detail view only, on the artwork itself. Not in the
result rows, and the reason is measured rather than aesthetic: the function
costs ~90ms per product because a verdict depends on its whole `source_id`
group, so it builds every group before filtering to one. A 50-row page would
spend 4.5 seconds to mark roughly a fifth of itself. If the grid ever wants
this, the function needs a cheaper path first — restricting to the relevant
`source_id` before grouping — and that is an optimisation to carefully
verified SQL, not a detail to slip into a UI change.

> **Done 2026-08-20** (Part 31 addendum). That restriction now exists: a
> single-product lookup narrows to one `source_id` before grouping and runs in
> ~5ms rather than ~96ms, verified byte-identical across all 26,166 rows. It
> was done on its own, with the invariants and test suite already in place —
> which was the point of saying it should not ride along inside a UI change.
> **Whether to mark ~19% of search rows is still an open product question**,
> and Part 25's lesson is the one to weigh: a marker earns its place by being
> withheld somewhere.

It sits at the **top** edge of the picture because `.variant-band` already
owns the bottom, and a card can carry both: `Aaron's Collection - 2009
(Tsubasa Nakamura)` shows "Base printing's art" above and "2009 World
Championship" below, which is the whole story in two lines.

The owner printing's name was in the band for one revision and came straight
back out. At the detail image's width it rendered as `Base printing's art —
Aar...`, so the band spent its last third on an ellipsis. The caution is the
message and the name is the detail, so the name moved to the tooltip.

No SQL changed. The function already accepted a `p_product_id`, so the whole
feature is a repository method, an endpoint, and a band.

### Checking it against the case it was built for

The demonstration is better than a passing assertion. Searching
`Aaron's Collection` returns the plain Rising Rivals printing and two World
Championship reprints. Select the 2009 reprint and the band appears; select
the plain printing and it does not — **and the two render the identical
picture.** The screen is showing you the borrowing at the same time it tells
you about it.

A `wasNull()` bug was caught before it ever ran. `owner_product_id` is null
for every `own_art` row, and the null test had been written inline inside the
record constructor, several `get` calls after the one it was meant to
describe — so it would have reported on `provenance` instead and turned every
absent owner into product 0. JDBC's `wasNull()` refers to the most recent get
on that `ResultSet`; a constructor argument list is a sequence of gets, not a
snapshot. The live `own_art` response returning `"ownerProductId": null`
is the check that it is right.

The One Piece path was verified too, since it exercises the opposite branch:
no image exists at all, so the provenance call 404s, the band never renders,
and the console shows the same ordinary 404 the thumbnail already produces.

**The pattern worth keeping:** a marker earns its place by being *withheld*
somewhere. The wedge in Part 25 appeared on every labelled card and therefore
told the reader nothing; this band appears on 4,898 of 26,166 and is silent on
the 155 undetermined ones, so its presence carries information. The
discipline that made it useful was refusing to show it where the answer is
merely unknown.

---

## Part 30 — The mirror nothing was holding up

CLAUDE.md has said since Part 26 that `is_variant_word()` and
`ImportCardImages.isVariantWord()` are a "change both or neither" pair,
because one decides a row's label and the other decides its image, and a
disagreement means the caption and the picture were chosen by different
rules. Nothing had ever checked that they agree. This project has no test
framework at all, so the claim rested entirely on whoever last edited one
remembering the other existed.

Reading them side by side, the regexes are character-for-character identical
(`[A-Za-z]`, `^[A-Za-z]{0,4}[0-9]+[A-Za-z]?$`) and Java's `matches()` is a
full match, matching the SQL anchors. One line differed: **Java trims its
argument, SQL did not.**

### Checking rather than reasoning about it

The tempting move is to reason from that difference to a verdict. Instead
both were run over the real corpus — every trailing `[...]` and `(...)`
token in `products`, 2,547 distinct — with the Java method reached by
reflection, since it is package-private.

**Zero disagreements.** The mirror was intact, because `variant_label()`
btrims before both of its calls. Then the same 2,547 tokens padded with one
space either side: **81 disagreements**, every one of them SQL calling
something a variant that Java correctly refuses. And they are not arbitrary
— they are precisely the two shapes the rules exist to exclude:

```
DISAGREE  token=[ Z ]       sql=true  java=false
DISAGREE  token=[ XY73 ]    sql=true  java=false
```

`Z` is the `Unown [A]`..`[Z]` bug, the one the length floor was added for.
`XY73` is the `Latios EX (XY72)` bug, the one the card-code test was added
for. Both come back in full the first time a caller forgets to btrim.

So the fix went inside the function, where the Java port keeps it: a
qualifier test that only works when called politely is one bad call away
from being wrong, and the whole point of a mirrored rule is that neither
copy depends on its surroundings.

### The check found the bug in the fix

The obvious patch was bare `btrim(p_token)`. The new check file disagreed:
`verdict_ignores_tabs_and_newlines` came back `f`.

**Postgres `btrim(x)` strips spaces only.** Java's `String.trim()` removes
everything at or below `U+0020`, tabs and newlines included. So the one
argument form closed the space gap and left `E'\tZ\n'` reading `true` in
SQL and `false` in Java — a smaller version of the same bug, and one that
would have been committed with a passing-looking commit message, because the
2,547-token corpus contains no tabs.

The fix takes an explicit set, `btrim(x, E' \t\n\r\f\v')`. Java also
strips control characters below `U+0020` that are not in that set; those do
not occur in this catalog, and the check pins the cases that do.

That is the part worth keeping about check files: this one earned its place
inside an hour by failing against the change that created it.

### What it cost to be sure

`is_variant_word()` feeds `variant_label()`, which is on screen. The change
is provably invisible: `variant_label()` was captured for all 38,587
products before the edit and compared after — **byte-identical**, twice
(once per revision of the fix). Parity was re-confirmed against Java on the
plain corpus, the padded corpus, and eleven tab/newline cases by hand. All
three check files pass.

One false alarm is worth recording, because it looked far worse than the
real bug. Running the tab/newline corpus through the comparison harness
reported **2,163 disagreements out of 5,094** — from 2,547 tokens. The count
being double the input was the tell: the harness read tab-separated lines,
and tokens containing a literal newline split into two. The harness was
broken, not the code, which the eleven hand-checked cases then confirmed.
A number that cannot be explained is not a result yet, and "checked" being
twice "input" is the kind of arithmetic that says so before any debugging.

**The pattern worth keeping:** "these two must stay in sync" is a comment,
not a mechanism. Both copies here were correct, so a reviewer comparing them
would have signed it off — the defect was not in either implementation but
in the *contract between them*, which said nothing about who trims. Latent
divergence needs a corpus and an executable comparison to find; reading is
not enough, and neither is agreement on the inputs that happen to occur.

---

## Part 31 — Tests, at last

Until 2026-08-20 this project had no test framework. Not a thin one — none:
no JUnit dependency, no Surefire plugin, no `src/test` tree, zero `.spec.ts`
files, and a `package.json` still advertising an `ng test` script whose runner
had been pruned from devDependencies. `ProductPullTest` was the last test that
existed and Part 13 deleted it, correctly, for skipping the very invariant it
should have been guarding.

What stood in for tests was prose. CLAUDE.md is unusually good prose — but it
is advisory, and this session had just demonstrated three times over what that
costs: Part 27's retraction reached two Markdown files and not the javadoc it
was about; `is_variant_word` and its Java port had drifted in their *contract*
while both copies stayed individually correct; and CLAUDE.md cited a class
Part 13 had deleted, inside a hard invariant, for days.

### Making the checks that already existed able to fail

The three files in `sql/checks/` were correct the entire time. They only ever
ran when someone remembered them, which is not a property a guarantee should
rest on. `run-all.sh` runs all of them and exits non-zero on any false
assertion.

The only interesting property of a runner is whether it can go **red**, so
that was checked directly rather than assumed: a deliberately failing check
exits 1 and prints the offending row; a check whose SQL is broken exits 1
rather than reporting a pass on empty output; and a check returning no rows is
a failure, because it asserted nothing. Matching `f` as a whole field rather
than a substring is what keeps a set legitimately named `Fossil` from tripping
it.

### The tests corrected the person writing them

Tier 1 covers the pure string rules, where this project's defects have
genuinely concentrated. Every case is a real printing — `Unown [A]`..`[Z]`,
`Latios EX (XY72)`, `Aerodactyl (1)`, `Rillaboom V - SWSH014` — because each
rule was added when a specific card broke something, and a test that drifts to
synthetic inputs stops defending the thing it was written for.

Five of the first 33 failed, and **all five were the test being wrong**:

* `numberDigits("004")` returns `4`, not `004`. Leading zeros are padding, so
  the printed `004/102` matches catalog card 4 — and they are dropped by
  string rather than `parseInt` because a pathological run of digits would
  overflow an int and the numeric value is never wanted.
* `strippedQualifier` lowercases. CLAUDE.md says it records the word
  "verbatim", which turns out to mean *it never folds spelling variants* —
  `semi-finalist` and `semi finalist` stay distinct — not that it preserves
  case.

Both behaviours are correct, deliberate, and documented in the code itself.
The tests now pin them, and the second one is worth noting for a different
reason: "verbatim" was doing more work in the doc than the code actually
promised, and only writing an assertion surfaced the gap.

### Two refactors, for a reason worth stating

`ImageDir.resolve()` and the `last-updated.txt` parsing were both split so the
decision could be called directly: `resolve(cwd, configured)` and
`parseLastUpdated(body)`.

This was not tidying. `user.dir` cannot be changed once a JVM is running, so a
test that could not supply its own working directory would have had to
reimplement the marker rule and assert against its own copy — and **a test
that reimplements the thing it is testing proves nothing**. The first draft of
`ImageDirTest` did exactly that before it was thrown away. The parsing split
has a second motive: tcgcsv is a free, no-SLA mirror with a documented ban
threshold, and a test suite runs far more often than a daily pull, so no test
here touches the network at all.

The assertion that matters in each is the refusal. `ImageDir` must refuse
**before** `createDirectories`, because the decoy tree left behind is what
made the original bug invisible; `parseLastUpdated` must refuse rather than
fall back to a clock, because that fallback is what wrote the phantom day.

### Turning prose rules into failing builds

The guard tier asserts facts about the codebase rather than its behaviour, and
it is both the cheapest to write and the closest thing here to enforcement of
the rules that actually matter: no outbound host beyond the three permitted
ones, no `<img>` resolving anywhere but our own API, `price_snapshots` never
the target of an UPDATE or `DO UPDATE`, no bare `LocalDate.now()`, the live
pull's date coming from `dataAsOf()` and not a clock, no two `<option>`
sharing an `[ngValue]`, and every identifier CLAUDE.md cites in backticks
existing somewhere in the source.

That last guard **failed on its first run**, naming `ProductPullTest` — the
same stale reference fixed by hand hours earlier, now cited deliberately as
history ("`ProductPullTest` did too, until Part 13 deleted it"). The fix was
an explicit list of names-cited-as-history with the quoting sentence in a
comment, not a pattern sniffing for words like "deleted" nearby. Same call as
the tournament qualifier family in Part 27, same reason: a pattern would
quietly excuse a genuinely stale reference that happened to sit in the wrong
sentence.

Fifty-five tests, all green, and the whole suite runs in under two seconds
because nothing in it needs a database or a network.

**The pattern worth keeping:** a rule that lives only in prose is enforced by
whoever last read it. Every guard in this part existed as a clearly-written
sentence in CLAUDE.md *before* the thing it forbids happened anyway. The
sentence was not the problem — the absence of anything that could fail was.

### Addendum — the remaining tiers, same day

The rest went in immediately after: **110 Java tests, 9 front-end tests, 3 SQL
checks.**

`TestDatabase` creates a throwaway database per test class, loads the real
`schema.sql` and `functions.sql` rather than a copy, and drops it in teardown.
Two decisions carried the weight. It loads the REAL schema, because a fixture
schema that drifts tests something that does not exist. And DB-backed tests
**skip** rather than fail when no container is running, so the pure unit tests
keep costing nothing — a developer without Docker up still gets a useful
`mvn test` instead of a wall of red.

The fixtures are hand-sized so every expected value is arithmetic: seven days
at 100 then three at 300 is +200%, and a mean reports +80%. Snapshotting real
output would have pinned whatever the functions currently do, bug included,
which is exactly how a set worth $2,792.05 could have been locked in at
$25,100.93.

Three entry points were split so tests could call the real decision rather
than approximate it — `ImageDir.resolve`, `parseLastUpdated`, and
`ApiServer.createApp`. The last one lets the API be started on an ephemeral
port against a throwaway database, which is what makes the contract testable
over real HTTP rather than by reading handlers: no endpoint returns
`high_price` (checked across seven endpoints, for the field name *and* the
99,999 value the fixtures park there), a crafted `../` path 404s instead of
serving a file outside the image root, and provenance reports an absent owner
as `null` rather than 0 — the `wasNull()` bug, now unable to return quietly.

**The parity harness, and the proof it works.** Tier 3 runs both
implementations of the mirrored rule over a shared corpus, in-process. Whether
it would actually catch the Part 30 bug was not assumed: a scratch database
was built with the PRE-FIX untrimmed definition installed in that database
alone, and the comparison run against it.

```
as shipped   plain=0  padded=0   tab/newline=0
pre-fix      plain=0  padded=15  tab/newline=15    e.g. [ Z ] sql=true java=false
```

**Plain tokens agree even when the function is broken**, because every caller
btrims. That single line explains why the drift survived review, why the
original bug went unnoticed, and why the perturbation cases — not the
straightforward one — are the tests actually doing the work. A test written
only against the inputs that occur would have passed happily through it.

The front end finally has a runner. `package.json` had advertised an `ng test`
script whose runner had been pruned from devDependencies, so the command
failed and `angular.json` had no test target at all. Vitest replaced it,
chosen over Angular's builder because what is worth testing there is pure —
the containment rule from Part 25 and the largest-catalog default — and
standing up a browser and TestBed to exercise two functions costs more than it
protects.

**The second pattern worth keeping:** a test suite's own honesty needs
checking too. Three of the things written today were verified by making them
fail on purpose — the check runner against a deliberately false assertion, the
parity harness against the pre-fix function, and five Tier 1 expectations that
turned out to be wrong about the code rather than the reverse. A green suite
that has never been seen red is a claim, not evidence.

---

## Part 32 — The letters in the card number

94.1% image coverage had been sitting there since Part 26, and the class
comment's story about the remainder was that it was upstream absence. Part 21
had already caught that story being wrong once — "World Championship decks /
jumbo / prize packs / promos are absent entirely" turned out to be two
normalization defects — so the claim was worth re-auditing rather than
inheriting.

### Triaging the 1,580 before writing anything

The audit was a throwaway script, not a code change: take every `no_match`
row, and ask the cached catalog whether a card by that name exists upstream at
all. Deliberately looser normalization than the matcher's, because the
question it answers — *does this card exist* — is the one robust to
normalization detail.

```
   716  name upstream, number differs
   640  name absent upstream
   192  NAME+NUMBER EXIST UPSTREAM
    32  product has no card number
```

That third line is the finding. 192 rows where the catalog demonstrably holds
a card of that name and that number, and the matcher had recorded a permanent
miss anyway.

**And the largest single family is genuinely absent, which is worth recording
so nobody re-derives it.** The Trainer Kits — XY, BW, HGSS, SM, DP, eight sets
and 412 rows — are not on pokemontcg.io. It carries `tk1a`/`tk1b`/`tk2a`/`tk2b`
(the 2003-era EX kits) and nothing since. The four "Trainer Gallery" sets that
match a name search are a different thing entirely. Those 412 are the same
category as the 61 `failed` McDonald's rows: an upstream gap, not a rule
defect. `Battle Academy 2024` (135) is likewise absent outright.

### The signal that was being thrown away

`numberDigits()` was written to find digits anywhere in a number rather than
only at its start — that was Part 21's fix, and it was right. But it returns
only the digits. The letters are discarded.

For most cards that is correct; the prefix is noise. For a holo subset or a
promo series it is the *only* thing identifying the printing, and discarding
it loses the match twice over. Aquapolis is the clean example:

- TCGplayer numbers the holo subset `H02/H32` — a 32-card subset.
- pokemontcg.io numbers the same card `H2` inside the parent set of **147**.
- So the printed totals disagree, and the strict key (name+number+total) misses.
- The bare digits are then `2`, which collides with every other card #2 in
  every other set — so the loose key is ambiguous, and refuses. Correctly:
  a confidently wrong image is worse than a blank for someone pricing stock.

The prefix is what breaks that tie, and it had been dropped before it could.

### A tier that can only narrow

`numberCode()` keeps the prefix: `H02/H32` → `h2`, `BW64` → `bw64`,
`SWSH039` → `swsh39`. A third index keyed on it sits **between** strict and
loose, in both tiers.

The property that makes this safe is worth stating precisely, because it is
what makes the change reviewable at all: **every card keyed `arcanine|h2` is
also keyed `arcanine|2`.** The new key is a subset of the loose key, so it can
turn an ambiguous candidate list into a single hit, and it cannot introduce a
candidate the loose key did not already hold. It narrows; it never widens.

It returns null for a bare number on purpose. Without a prefix the key would
be character-for-character the loose key, so the tier would add no
discrimination — only a second decision point reporting a different rule name
for a match already made.

### Measured twice, by two implementations

The rule was modelled in Python against all 27,807 Pokemon singles first, to
size it before committing to Java:

```
old rule matched: 26,224     new rule matched: 26,313
GAINED 89        CHANGED target 0        LOST 0
```

Then implemented, rebuilt, and run through `--dry-run`, which uses the real
matcher:

```
before   checked 1,641   would match  61   would miss 1,580
after    checked 1,641   would match 150   would miss 1,491
```

**150 − 61 = 89.** Two independent implementations agreeing to the row is what
makes the number trustworthy rather than plausible; a single measurement here
would only have proved the model consistent with itself.

The recovered matches are eyeball-checkable, which was the other point of
doing it this way — `Ariados (H3)` `H03/H32` → Aquapolis `#H3`,
`Magcargo (H17)` → Skyridge `#H17`, `Vulpix (Shiny)` `SH6` → Platinum `#SH6`,
`Kyogre (Shiny)` `SL6` → Call of Legends `#SL6`, `Pikachu - SM04` → SM Black
Star Promos `#SM04`. Fifteen sets in all.

### Why no existing image could be redirected

Worth stating because it is the question a matching-rule change should always
have to answer, and here the answer is structural rather than measured:
`productsNeedingImages()` selects rows that are NULL, `failed`, or — only
under `--dry-run` — `no_match`. **It never returns an `ok` row.** A rule change
therefore cannot redirect an image that already exists; it can only fill a
gap. That property now carries a comment on the query itself, since it is easy
to lose in a refactor and it is the whole safety argument.

### --skip-failed

CLAUDE.md says the 61 remaining `failed` rows are an upstream gap and the
importer should not keep being re-run for them — ~6 requests each against a
free community API for no expected gain. But `failed` rows are normally
retried, which is right for a transient blip, so that rule lived only in prose
and only worked if someone remembered it.

`--skip-failed` makes it enforceable. With it, the pending re-check is exactly
the 1,580 `no_match` rows and nothing else.

### The tests, and seeing them red

Five tests on `numberCode()`: the prefix is kept, leading zeros are padding on
this side too (or `H02` misses the catalog's `H2`), the denominator is never
read as the number, a bare number gives null rather than digits, and a prefix
too long to be one is refused. One of them asserts the narrowing property
directly — every code must end in its own `numberDigits()` — because that is
the invariant the safety argument rests on, not an incidental behaviour.

Per Part 31's rule, they were seen red before being trusted: reverting
`numberCode()` to return the digits alone (i.e. regressing it to
`numberDigits()`) fails three of the five, `expected: <h2> but was: <2>`.

### The run, and what it confirmed

The owner authorised the clear the same day. `no_match` is a permanent
negative cache, so realising the gain meant deleting those 1,580 rows and
letting one `--skip-failed` run rebuild them:

```
DELETE 1580
Done. matched=89 (downloaded=89, failed=0), no_match=1491
```

Which lands exactly where both measurements said it would:

| | before | after |
|---|---|---|
| `ok` | 26,166 | **26,255** |
| `no_match` | 1,580 | 1,491 |
| `failed` | 61 | 61 |
| coverage | 94.1% | **94.42%** |

`failed` unmoved is `--skip-failed` doing its job — the 61 upstream-dead rows
were not touched, and not one request was spent on them. Files on disk came
to 26,255, matching the `ok` count exactly, which is the check worth doing
after any import: a mismatch there means the writer and the reader disagree
about where the image tree is, which has happened before (Part 28). By rule,
64 matched at tier 1 and 25 at tier 2 as base art.

**Spot-checked as pictures, not as row counts**, because the whole premise is
that a confidently wrong image is worse than a blank. `Vulpix (Shiny)` →
`pl1-SH6` is gold rather than the usual orange, so it really is the shiny
printing; `Ariados (H3)` → `ecard2-H3` carries the e-Card frame and its
dotted code strip, which only Aquapolis-era cards have. Both served over the
API as real JPEGs.

### The verdict counts moved, and the shrug count did not

```
own_art  21,113 -> 21,181   (+68)
base_art  4,898 ->  4,919   (+21)
unknown     155 ->    155   ( 0)
```

+68 and +21 account for all 89. The third line is the interesting one. Six of
the qualifiers these matches introduced — `general mills promo`, `general
mills`, `shiny`, `target non holo`, `full art`, `build a bear workshop
exclusive` — are not in `image_qualifier_class` at all, and *none* of them
produced an `unknown`. That is Part 27's fallback working as designed rather
than as hoped: an unclassified qualifier defers to the structural test
instead of becoming a shrug, which is precisely why leaving the vocabulary
incomplete is safe rather than merely tolerable.

`shiny` is the one worth the owner's attention, and it is genuinely
undecidable from here: it covers Platinum's `SH` and Call of Legends' `SL`
subsets where the shiny is different artwork — arguing `identity` — while the
same word elsewhere names a foil treatment, arguing `treatment`. Exactly the
split Part 27 says cannot be derived. It is in the backlog, not guessed at.

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
| Set-level return | Sum of all prices on day A vs. sum on day B (portfolio-style), not average of individual returns | Value-weighted, matches "what happened to the set's total worth" rather than "what did a typical card do"; see Part 12 |
| `is_sealed` classification | Presence of a `Number` field in `extendedData` | Data-driven, not name-pattern guessing; verified across 1999 Base Set through 2026 sets before trusting it; see Part 12 |
| Set-level price/return and sealed product | Sealed product hardcoded out of `set_return()`/`set_value()` - not a default, no parameter to override it | A set's value is categorically about its singles; sealed items stay trackable individually via `item_return()`/`item_all_time_high()`; see Part 14 |
| All-time-high formula | `MAX(COALESCE(market_price, mid_price, low_price))`, not plain `MAX(market_price)` | Reconciled with the general trending-fallback rule; a MAX() over all history doesn't carry the anchor-bias risk a two-point comparison does; see Part 14 |
| Which games to track | `tracked_categories` table (data), not a hardcoded category ID (code) | Adding a game becomes an INSERT, not a rebuild; see Part 15 |
| Game-specific quirks (code cards, etc.) | Scoped explicitly to the game they were discovered in, never broadened to "all games" | Pokemon's known quirks aren't assumed to be universal trading-card facts; see Part 15 |
| `ingest_runs.source` naming | Category-scoped (`tcgcsv-3`, `tcgcsv-archive-3`), existing rows migrated via `UPDATE` | Each tracked game needs independent idempotency/failure tracking; migrated rather than left to silently retrigger a full re-pull; see Part 15 |
| Search result granularity | One row per product with `variant_count`, never one per (product, variant) | A single "price" per product would collapse sub-types, the oldest invariant in the project; `item_variants()` carries the real prices; see Part 16 |
| Card number / rarity source | `extendedData` via `extended_field()`, never parsed from the product name | Only 5,276 of 34,435 singles carry a number in the name; `extendedData` has it for 100%. Same set reprints one card at several rarities across a 19x price range; see Part 16 |
| Card number matching | Per-game normalization (`normalize_card_number()`), not one generic digit rule | Pokemon's number is before the separator (`004/102`), One Piece's is after (`OP17-028`) — a generic rule silently matches set size or set code; see Part 17 |
| "name + number" parsing | In SQL (plpgsql `item_search` over `item_search_core`), not in the client | Same behaviour for psql, the API and the front end; parse-and-retry can't diverge per caller; see Part 17 |
| Wrong number guess | Retry the whole string as a plain name when the parsed reading returns zero rows | The last-token rule genuinely misfires (`Nemona - Pikachu 42` is number `180/198`); without the retry a real card looks missing; see Part 17 |
| `high_price` in the API | Not returned by `item_history()`, no field on `PricePoint` | Makes "store it, ignore it" structural instead of a rule a chart author has to remember; see Part 16 |
| Search result ordering | `rank, length(name), name, product_id` — `product_id` as final tiebreaker | Dozens of products share an identical name; without it, `LIMIT`/`OFFSET` paging silently duplicates and skips rows; see Part 16 |
| Product name search index | `pg_trgm` GIN index on `products.name` | Leading-wildcard `ILIKE` can't use a btree — was a full seq scan on every keystroke, ~30ms to ~1.3ms; see Part 16 |
| API DB connections | One connection per request, no pool | JDBC `Connection` isn't thread-safe and Javalin is multithreaded; a shared one corrupts intermittently rather than failing cleanly; see Part 16 |
| API framework | Javalin, not Spring Boot | A handful of read-only JSON endpoints over existing SQL functions doesn't warrant a framework; see Part 16 |
| Movers price basis | Mean of N days at each end, not two point observations | Thinly-traded cards lurch on single sales; measured to damp a one-day artifact ~3x while leaving a real move at full magnitude; see Part 19 |
| Movers minimum price | $1 floor on the starting price, by default | Percent change flatters cheap cards — unfiltered the top decliner was a $0.06 card moving 5 cents; see Part 19 |
| Movers row granularity | Per (product, variant), unlike search's per-product | A mover is one specific market's move; sub-types move independently; see Part 19 |
| Card image source | pokemontcg.io only, never TCGplayer | Owner sells on TCGplayer and eBay; permitted-hosts table now explicit in CLAUDE.md; see Part 20 |
| Image delivery | Downloaded once, downscaled, served from our own disk | Browser contacts no third party at all — stronger than hotlinking a permitted host; ~200MB vs ~2.6GB; see Part 20 |
| Unmatched cards | Recorded as `no_match`, but ONLY when the catalog download was complete | Otherwise a transient upstream 500 permanently marks a card imageless, since misses are never re-checked; see Part 20 |
| Ambiguous image matches | Rejected — no image rather than a guess | A confidently wrong card image is worse than a blank when pricing inventory; see Part 20 |
| SQL window bounds | Resolved to plpgsql DATE variables, never computed in a CTE | Bounds from a sub-select can't be folded to constants — planner spilled ~6GB to temp files, 550ms vs 365ms; see Part 19 |
| Image provenance (whose art is shown) | Decided by which other products claim the same pokemontcg.io card, computed on demand; never a stored verdict | One catalog card is one picture, so sharing is provable rather than inferred - and a later import matching a missing plain printing would silently invalidate a stored value; see Part 27 |

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

### Community art showcase — owner's idea, 2026-08-16

**The idea, in the owner's words:** a community showcase that highlights
what people vote for as favourite *art* over a period, with rotating
themed prompts rather than one generic "best card" - examples given were
"what card gives best beach vibes" and "favourite card using a darker
theme."

The rotating prompt is the interesting half. A single "best card" poll
converges on the same expensive chase cards forever; themed prompts
surface art that price data never would, and give a reason to come back
each period. It's also the first feature idea for this project that isn't
about money, which is probably the point.

**Correction, same day.** This was first written up here as a "pivot"
that contradicted a founding constraint, quoting "Not a product. No users,
no auth, no server, nothing public." The owner pushed back - they never
said that. Checking `git log -S` confirms it: the line entered in the
initial commit (f7e73c6) inside Claude-authored docs, as a description of
what the project *was* at the time, and was then quoted by later sessions
as though it were a prohibition the owner had issued. It wasn't. Corrected
in CLAUDE.md and README so it reads as current state rather than a rule.

Worth internalising as a pattern: a factual note about where a project
stands can harden into an imagined constraint purely through repetition,
and then get used to argue against the owner's own ideas. Anything in
these docs phrased as a limit should be traceable to something the owner
actually said.

What building it genuinely requires is unchanged and unremarkable: a
hosted server, user identity, write endpoints (the API is read-only today,
see Part 16), and anti-vote-gaming. Those are work items, not violations.

**One thing that does deserve a real answer first:**

1. **Card art licensing - the one with actual exposure.** Showing Pokemon
   art in a private local tool is a different act from serving it
   publicly at scale. The art belongs to The Pokemon Company;
   pokemontcg.io's terms cover access to *their API*, not a
   redistribution right for a public product. Given how carefully the
   owner's TCGplayer and eBay seller accounts are protected elsewhere in
   these rules (see CLAUDE.md), a public site built on someone else's
   card art under a name traceable to them deserves the same scrutiny -
   priced before building, not after.

**Suggested staging, which sidesteps that for now:** build it single-player
first - themed prompts, browse art, pick favourites, view your picks per
period. Useful on its own, uses the images being imported in Part 20, and
crucially **the data model is identical to the multi-user version**:
prompts, periods, candidate items, votes. Nothing in the schema changes
if it later goes public; identity and hosting get added around it. That
way something exists now and the pivot stays a real decision rather than
one backed into.

**Confirmed 2026-08-16: the app is meant to ship**, so a multi-user
version is in scope from the start rather than something to grow into.
Identity should be in the schema from day one (votes reference a voter,
uniqueness enforced per (voter, prompt) in the database rather than in
application code) - vote integrity is the thing that cannot be retrofitted
cleanly.

**Three design questions the owner is thinking about, and their answers
shape the tables - do not guess at them:**

1. **What is a vote?** One favourite per prompt, a ranked top 3, or
   upvote-as-many-as-you-like. Three different schemas and three very
   different behaviours: single-pick is cleanest and hardest to game;
   multi-upvote surfaces more art but rewards whoever votes most.
2. **Where do candidates come from?** Any card in the catalog via open
   nomination, or a curated shortlist per prompt. Open is more fun;
   curated avoids someone nominating a basic energy card as a joke.
3. **What is a period?** Fixed (weekly/monthly) or opened and closed by
   hand. Determines whether prompts need a scheduler or just a status
   column.

Not started. Schema not yet designed.

### Other open items

- **One Piece added (Part 15)** — 918 days backfilled, catalog populated,
  `is_sealed` and the query functions all confirmed working against real
  data. A third game (or more) would be the next test of how well this
  generalizes further, but the architecture is no longer untested.
- **Further product categorization** — `is_sealed` now works (see Part
  12), but it's binary. If finer distinctions turn out to matter later
  (booster boxes vs. ETBs vs. tins, etc.), that's still open.
  **Checked 2026-08-13:** graded singles (PSA/BGS/CGC) are not one of
  those distinctions — verified live against tcgcsv's full category list
  (no dedicated "graded" category among all ~90) and ~1,830 sampled
  Pokemon products across four groups (no product name or `extendedData`
  field references grading anywhere). TCGplayer's marketplace sells by
  condition (NM/LP/MP/etc.), not as graded slabs — that's a different
  market (PSA/PWCC/eBay), not something tcgcsv carries. The earlier
  mention of "graded singles vs. raw" here was a speculative example, not
  a verified gap; removed.
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
- ~~**Front end**~~ — built (Part 18). Angular 22 in `web/`, Card /
  Product Search working against the read API. Further screens (movers,
  set overview) are the next additions.
- ~~Open: product thumbnails hit `tcgplayer-cdn.tcgplayer.com`~~ —
  resolved 2026-08-15 (Part 18): removed entirely, verified zero requests
  to any TCGplayer host. The rule is now explicit in CLAUDE.md that this
  includes CDN subdomains and images, not just crawling.
- ~~**Set overview screen**~~ — built (Part 23). `GET /api/sets/overview`
  and a third tab in `web/`. Note it did need new SQL after all
  (`game_set_overview()`, a set-wide single pass — the per-set primitives
  would have been 604 round trips).
- **Oscillating `market_price` on thin markets — open, and a domain call.**
  Found while building Part 23. Some cards' `market_price` toggles between
  two very different values for weeks at a time (Base Set Shadowless
  Charizard 1st Ed Holo: ~$250 vs exactly $10,000, with low/mid/high parked
  at $100,000). It is not a one-day spike, so neither
  `item_movers()`-style smoothing nor Part 24's despiking touches it. The set overview *discloses* the effect rather than
  correcting it, which is the honest thing to do at the presentation layer,
  but the underlying question is unanswered: **should readings like these
  be flagged, or treated as suspect at ingest?** That is the same kind of
  judgment as the code-card exclusion and the sealed rule — a domain call
  about what the data means, not an implementation detail. It also affects
  Movers and all-time-high, not just this screen. Do not guess at it.
  Data to hand: 44 of 283 sets have >50% of their 30-day movement from a
  single row.
- **API hardening if it ever leaves localhost** — connection-per-request
  should become a pool (`ApiServer.withRepo` is the only place to
  change), and the CORS rule would need revisiting. Fine as-is for a
  local single-user tool.
  **Quantified 2026-08-20.** Every request opens its own JDBC connection,
  including each thumbnail, so the ceiling is Postgres' `max_connections`
  (100 by default). Measured against the running server: 80 concurrent image
  requests all 200, 200 concurrent all 200, and at 300 mixed image+search
  requests **50 returned 500**, the API log carrying exactly 50
  `FATAL: sorry, too many clients already`. It recovers by itself — health
  and search were 200 immediately after, with connections back to 6.
  A realistic single-user page is ~50 concurrent thumbnails and passes with
  room to spare, which is why this has never been felt. Two things follow:
  the fix is a pool and therefore a new dependency on a deliberately small
  dependency list, which is the owner's call; and the number to design
  against is now known rather than guessed.
