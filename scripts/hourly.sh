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

say "done."
