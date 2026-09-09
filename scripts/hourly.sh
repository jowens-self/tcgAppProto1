#!/usr/bin/env bash
#
# Runs hourly. Does nothing at all unless the database container is ALREADY up.
#
# This does not contradict the 2026-08-13 decision against a pull daemon; it
# is built around it. That decision was about not keeping Docker Desktop
# resident for nobody. This never starts Docker, never wakes it, and exits in
# about a second when it is down. What it does is notice the hours when the
# container is up because someone is working, and use one of them.
#
# Hourly rather than nightly for that same reason: "already running" is true
# during the working day and false at 03:00, so a nightly job guarded this way
# would never fire at all. Hourly costs one `docker exec true` and one indexed
# query on the hours it does nothing, and starts a JVM at most once a day.
#
# Two steps, in order:
#   1. Pull, unless a live pull already succeeded today (UTC). App does its own
#      gap catch-up, so one pull covers however many days were missed.
#   2. Export whatever has settled into the archive repo.
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

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB_CONTAINER="${DB_CONTAINER:-cardtracker-db}"
JAR="${JAR:-$REPO_ROOT/target/cardtracker-0.1.0.jar}"
SKIP_PULL="${SKIP_PULL:-false}"
SKIP_EXPORT="${SKIP_EXPORT:-false}"

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

# --- 1. pull, at most once a day ---------------------------------------------
if [ "$SKIP_PULL" != "true" ]; then
  # Live-pull sources are 'tcgcsv-<categoryId>'; the archive backfill writes
  # 'tcgcsv-archive-<categoryId>' and is not what this is asking about.
  PULLED_TODAY="$(docker exec "$DB_CONTAINER" psql -U cardtracker -d cardtracker -t -A -c "
    SELECT count(*) FROM ingest_runs
    WHERE source LIKE 'tcgcsv-%'
      AND source NOT LIKE 'tcgcsv-archive-%'
      AND status = 'ok'
      AND (started_at AT TIME ZONE 'UTC')::date = (now() AT TIME ZONE 'UTC')::date;")"

  if [ "${PULLED_TODAY:-0}" -gt 0 ]; then
    say "pull: already ran today (UTC); skipping."
  elif [ ! -f "$JAR" ]; then
    say "pull: no jar at $JAR; skipping (mvn package)."
  else
    say "pull: starting."
    # App decides what to fetch and runs its own gap catch-up. A failure here
    # must not stop the export -- yesterday's data is still worth archiving.
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
  SELECT string_agg(g, ', ')
  FROM (SELECT c.name || ' (' ||
               (CURRENT_DATE - COALESCE(MAX(ps.snapshot_date), DATE '1970-01-01')) ||
               CASE WHEN CURRENT_DATE - COALESCE(MAX(ps.snapshot_date), DATE '1970-01-01') = 1
                    THEN ' day)' ELSE ' days)' END AS g
          FROM tracked_categories tc
          JOIN categories c ON c.category_id = tc.category_id
          LEFT JOIN products p ON p.category_id = tc.category_id
          LEFT JOIN price_snapshots ps ON ps.product_id = p.product_id
         GROUP BY c.name
        HAVING COALESCE(MAX(ps.snapshot_date), DATE '1970-01-01') < CURRENT_DATE - 2) x;" 2>/dev/null)"

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

say "done."
