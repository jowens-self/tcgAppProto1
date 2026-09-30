#!/usr/bin/env bash
#
# Runs hourly (com.cardtracker.hourly) and once more at the 16:30 pull slot
# (com.cardtracker.daily). Does nothing at all unless the database container
# is up.
#
# Until 2026-09-30 this was built around the 2026-08-13 decision against a
# pull daemon: it never started Docker and only used the hours the container
# happened to be up. That decision rested on tcgcsv's archive making a missed
# day free, and the archive is gone (Part 42), so the owner reversed it: Docker
# now opens at login (com.cardtracker.docker-at-login) and cardtracker-db has
# restart: unless-stopped. This script still never starts Docker itself; the
# `docker exec true` guard below now just covers the minutes after login
# before Docker is ready.
#
# Steps, in order:
#   1. Pull if the day that should be held is missing for any tracked game.
#      Before SLOT that is yesterday, from SLOT on it is today, so the pull
#      happens at the 16:30 slot (24 hours apart, as tcgcsv asks) and the
#      hourly runs only retry: a failed slot, a late refresh, a Mac that was
#      off at 16:30. App skips any game already holding the day, so a retry
#      fetches only what is missing.
#   2. Export whatever has settled into the archive repo.
#   3. Check the database is still receiving prices.
#   4. Check the archive repo is committed and pushed.
#
# A day pulled at 14:00 is not exported at 14:00: the exporter leaves a day
# alone until nothing has been written to it for SETTLE_MINUTES. The 15:00 run
# takes it. That lag is the guard working, not a delay to tune out.
#
# Env knobs:
#   DB_CONTAINER  postgres container name (cardtracker-db)
#   JAR           path to the app jar (target/cardtracker-0.1.0.jar)
#   SKIP_PULL     "true" to export only
#   SKIP_EXPORT   "true" to pull only
#   DATA_REPO     archive repo to check in step 4 (default ../cardtracker-data)
#   SLOT          daily pull time, HHMM local (1630); must match the Hour and
#                 Minute in com.cardtracker.daily.plist
#   CLOCK_HHMM    pretend the local time is this HHMM (testing only)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB_CONTAINER="${DB_CONTAINER:-cardtracker-db}"
JAR="${JAR:-$REPO_ROOT/target/cardtracker-0.1.0.jar}"
SKIP_PULL="${SKIP_PULL:-false}"
SKIP_EXPORT="${SKIP_EXPORT:-false}"
DATA_REPO="${DATA_REPO:-$(cd "$REPO_ROOT/.." && pwd)/cardtracker-data}"
SLOT="${SLOT:-1630}"

say() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# --- the whole point: only when someone else already brought it up -----------
docker exec "$DB_CONTAINER" true 2>/dev/null || exit 0

# --- one run at a time --------------------------------------------------------
# launchd will not start a second copy of a job that is still running, but a
# hand-run copy alongside the scheduled one is not covered by that. mkdir is
# the atomic primitive available everywhere; macOS has no flock.
LOCK="/tmp/cardtracker-hourly.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  say "another run holds $LOCK; exiting."
  exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

cd "$REPO_ROOT"

# --- 1. pull, when the day that should be held is missing -------------------
if [ "$SKIP_PULL" != "true" ]; then
  # Which snapshot should we hold by now? tcgcsv refreshes at ~20:00 UTC and
  # the pull slot is SLOT local, after that. Before the slot, the newest day we
  # can expect is yesterday's; from the slot on, it is today's. (At 16:30 local
  # the UTC date is the local date in both CDT and CST, so the snapshot tcgcsv
  # is serving is dated today.)
  HHMM="${CLOCK_HHMM:-$(date +%H%M)}"
  if [ "$((10#$HHMM))" -lt "$((10#$SLOT))" ]; then
    EXPECTED="$(date -v-1d +%F)"
  else
    EXPECTED="$(date +%F)"
  fi

  # Tracked games that do not yet hold EXPECTED. Counted per game on purpose:
  # an earlier version asked whether ANY pull had succeeded today, and a game
  # that failed while another succeeded was then never retried (Part 42). A
  # failed query leaves this empty, and empty means pull -- App is safe to run
  # with nothing to do.
  MISSING="$(docker exec "$DB_CONTAINER" psql -U cardtracker -d cardtracker -t -A -c "
    SELECT count(*) FROM tracked_categories tc
    WHERE NOT EXISTS (
      SELECT 1 FROM ingest_runs r
      WHERE r.source IN ('tcgcsv-' || tc.category_id, 'tcgcsv-archive-' || tc.category_id)
        AND r.status = 'ok'
        AND r.snapshot_date >= DATE '$EXPECTED');" 2>/dev/null)"

  if [ "${MISSING:-1}" -eq 0 ] 2>/dev/null; then
    say "pull: every tracked game holds $EXPECTED; skipping."
  elif [ ! -f "$JAR" ]; then
    say "pull: no jar at $JAR; skipping (mvn package)."
  else
    say "pull: starting (${MISSING:-?} game(s) missing $EXPECTED)."
    # App decides what to fetch and skips what it already holds. A failure
    # here must not stop the export -- yesterday's data is still worth
    # archiving.
    if /usr/bin/java -jar "$JAR"; then
      say "pull: done."
    else
      say "pull: FAILED (exit $?); continuing to export."
    fi
  fi
fi

# --- 2. export whatever has settled ------------------------------------------
if [ "$SKIP_EXPORT" != "true" ]; then
  say "export: starting."
  "$REPO_ROOT/scripts/snapshot-export.sh" || say "export: FAILED (exit $?)."
fi

# --- 3. did any of that actually work? ---------------------------------------
#
# Part 40: this job failed every hour for five days and nothing noticed,
# because a launchd job that cannot reach its own repository exits without
# writing anything - and an empty log is indistinguishable from a quiet
# success. Checking the exit status above would not have caught it either;
# the script never ran far enough to have one.
#
# So this asks the database instead. Two days rather than one, because
# snapshot_date anchors to the mirror's dataAsOf() and a healthy system is
# one day behind by design.
STALE="$(docker exec "$DB_CONTAINER" psql -U cardtracker -d cardtracker -t -A -c "
  SELECT string_agg(game || ' (' || days_stale ||
                    CASE WHEN days_stale = 1 THEN ' day)' ELSE ' days)' END, ', ')
    FROM data_freshness() WHERE days_stale > 2;" 2>/dev/null)"

if [ -n "${STALE//[[:space:]]/}" ]; then
  say "STALE: price data has stopped arriving for $STALE"
  # A log line alone is what failed last time, so also raise something the
  # owner sees without opening anything. Best-effort: osascript is absent
  # under some launchd contexts, and a missing notification must never make
  # an otherwise fine run look failed.
  /usr/bin/osascript -e "display notification \"Price data stale: $STALE\" with title \"CardTracker\"" 2>/dev/null || true
else
  say "freshness: ok."
fi

# --- 4. is the ARCHIVE intact, as opposed to the database? --------------------
#
# Step 3 asks the database, which knows nothing about the archive repo. On
# 2026-09-15 an export wrote a file and died before committing it: the database
# stayed perfectly fresh, so every run for the next six days logged
# "freshness: ok" over a repo that was quietly missing a day. Part 40's lesson
# again, one layer out -- the check has to ask the thing that can actually be
# wrong.
#
# Uncommitted files under data/ mean a run died mid-day. Unpushed commits mean
# the off-machine copy -- the only reason this repo exists -- is behind the one
# on this Mac. Both are cheap to ask and neither shows up anywhere else.
archive_alarm() {
  say "ARCHIVE: $1"
  /usr/bin/osascript -e "display notification \"$1\" with title \"CardTracker\"" 2>/dev/null || true
}

if [ ! -d "$DATA_REPO/.git" ]; then
  archive_alarm "no repo at $DATA_REPO"
# A probe that cannot run must not be read as a pass -- that is the whole
# mistake this step exists to stop making. git here is /usr/bin/git, the Xcode
# command-line-tools shim, and it refuses to run at all (exit 69, one line on
# stderr) for as long as an Xcode update leaves the licence unaccepted. That is
# not a hypothetical: it is what broke the 2026-09-15 export in the first place.
elif ! DIRTY="$(git -C "$DATA_REPO" status --porcelain -- data 2>&1)"; then
  archive_alarm "cannot read the archive -- git: $(printf '%s' "$DIRTY" | head -1)"
elif ! UNPUSHED="$(git -C "$DATA_REPO" log --oneline '@{u}..' 2>&1)"; then
  archive_alarm "cannot compare with the remote -- git: $(printf '%s' "$UNPUSHED" | head -1)"
else
  N_DIRTY="$(printf '%s' "$DIRTY" | grep -c . || true)"
  N_UNPUSHED="$(printf '%s' "$UNPUSHED" | grep -c . || true)"
  if [ "$N_DIRTY" -gt 0 ] || [ "$N_UNPUSHED" -gt 0 ]; then
    archive_alarm "$N_DIRTY uncommitted file(s), $N_UNPUSHED unpushed commit(s)"
  else
    say "archive: in sync."
  fi
fi

say "done."
