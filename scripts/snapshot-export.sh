#!/usr/bin/env bash
#
# Exports each COMPLETED day of price_snapshots to a gzipped CSV in the data
# repo, one commit per day, and pushes.
#
# The point is a second copy of the price history that does not live in the
# Docker volume. 41M rows and 900+ days currently exist in exactly one place;
# if that volume goes, the backfill to 2024-02-08 is not reproducible.
#
# Idempotent. A day that already has a file is never exported again, so this
# is safe to run on a timer, by hand, or twice in a row. Days are found by
# diffing what the database has against what the data repo is missing, so a
# missed week catches itself up on the next run with no bookkeeping.
#
# Commit timestamps are whenever the export actually ran. Nothing is backdated.
#
# Env knobs (all optional):
#   DATA_REPO       where the data lives   (default ../cardtracker-data)
#   SETTLE_MINUTES  a day is "done" once no row has landed for this long (30)
#   MIN_ROW_RATIO   skip a day below this fraction of its LOCAL median (0.90)
#   WINDOW          days either side used for that local median (10)
#   MAX_DAYS        stop after this many days in one run (0 = no limit)
#   DB_CONTAINER    postgres container name (cardtracker-db)
#   DRY_RUN         "true" to print the plan and change nothing
#   NO_PUSH         "true" to commit locally without pushing

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA_REPO="${DATA_REPO:-$(cd "$REPO_ROOT/.." && pwd)/cardtracker-data}"
SETTLE_MINUTES="${SETTLE_MINUTES:-30}"
MIN_ROW_RATIO="${MIN_ROW_RATIO:-0.90}"
WINDOW="${WINDOW:-10}"
MAX_DAYS="${MAX_DAYS:-0}"
DB_CONTAINER="${DB_CONTAINER:-cardtracker-db}"
DRY_RUN="${DRY_RUN:-false}"
NO_PUSH="${NO_PUSH:-false}"

die() { echo "snapshot-export: $*" >&2; exit 1; }

psql_q() {
  docker exec "$DB_CONTAINER" psql -U cardtracker -d cardtracker -t -A -v ON_ERROR_STOP=1 "$@"
}

# --- preflight ----------------------------------------------------------------
# Docker is off here more often than not: pulls are on demand by the
# 2026-08-13 decision, so the container is up only while someone is working.
# Nothing to export is therefore the normal morning, not a fault -- exiting
# non-zero would write a red mark into the log every day and train everyone
# to ignore it.
if ! docker exec "$DB_CONTAINER" true 2>/dev/null; then
  echo "Container '$DB_CONTAINER' is not running; nothing to export."
  exit 0
fi
[ -d "$DATA_REPO/.git" ] \
  || die "no git repo at $DATA_REPO -- create it first, or set DATA_REPO"

# --- every settled day and its size, in one pass ------------------------------
# A day is settled when nothing has been written to it recently. That is the
# guard against exporting a pull still in flight: the export would capture a
# partial day, write the file, and then never revisit it.
DAYCOUNTS="$(psql_q -F'|' -c "
  SELECT snapshot_date, count(*)
  FROM price_snapshots
  GROUP BY snapshot_date
  HAVING max(ingested_at) < now() - interval '$SETTLE_MINUTES minutes'
  ORDER BY snapshot_date;")"

[ -n "$DAYCOUNTS" ] || { echo "No settled days in the database."; exit 0; }

# --- judge each day against its NEIGHBOURS, not against today -----------------
# The catalog grows: ~38k products in early 2024, ~51k now. A global median
# would read every 2024 day as a truncated pull. A partial pull is a dip
# against the days either side of it, which is what this measures.
PLAN="$(printf '%s\n' "$DAYCOUNTS" | awk -F'|' -v W="$WINDOW" -v R="$MIN_ROW_RATIO" '
  { day[NR]=$1; rows[NR]=$2 }
  END {
    for (i = 1; i <= NR; i++) {
      lo = i - W; if (lo < 1)  lo = 1
      hi = i + W; if (hi > NR) hi = NR
      n = 0
      for (j = lo; j <= hi; j++) if (j != i) win[++n] = rows[j]
      if (n == 0) { print day[i], rows[i], rows[i], 0, "OK"; continue }
      # insertion sort; the window is at most 2*W entries
      for (a = 2; a <= n; a++) { v = win[a]; b = a - 1
        while (b > 0 && win[b] > v) { win[b+1] = win[b]; b-- }
        win[b+1] = v }
      med = (n % 2) ? win[(n+1)/2] : int((win[n/2] + win[n/2+1]) / 2)
      floor = int(med * R)
      print day[i], rows[i], med, floor, (rows[i] < floor ? "LOW" : "OK")
      delete win
    }
  }')"

TOTAL_SETTLED="$(printf '%s\n' "$PLAN" | wc -l | tr -d ' ')"
echo "Settled days in database: $TOTAL_SETTLED"

cd "$DATA_REPO"

EXPORTED=0; SKIPPED=0; PRESENT=0
while read -r DAY ROWS MED FLOOR VERDICT; do
  [ -n "$DAY" ] || continue
  YEAR="${DAY%%-*}"; MONTH="$(echo "$DAY" | cut -d- -f2)"
  OUT="data/$YEAR/$MONTH/$DAY.csv.gz"

  [ -f "$OUT" ] && { PRESENT=$((PRESENT + 1)); continue; }

  if [ "$MAX_DAYS" -gt 0 ] && [ "$EXPORTED" -ge "$MAX_DAYS" ]; then
    echo "Reached MAX_DAYS=$MAX_DAYS; stopping. Re-run to continue."
    break
  fi

  # A day well below its neighbours is a pull that died partway. Skipping
  # leaves it missing, so a later run picks it up once it has been backfilled
  # properly -- which is why this skips rather than warns and writes.
  if [ "$VERDICT" = "LOW" ]; then
    echo "  $DAY: SKIP -- $ROWS rows vs local median $MED (floor $FLOOR)"
    SKIPPED=$((SKIPPED + 1))
    continue
  fi

  if [ "$DRY_RUN" = "true" ]; then
    echo "  $DAY: would export $ROWS rows -> $OUT"
    EXPORTED=$((EXPORTED + 1))
    continue
  fi

  mkdir -p "$(dirname "$OUT")"
  # Ordered so the file is byte-stable: the same day exported twice is the same
  # bytes, which is what makes the archive checkable against the database.
  docker exec "$DB_CONTAINER" psql -U cardtracker -d cardtracker -v ON_ERROR_STOP=1 -c "
    COPY (
      SELECT product_id, sub_type_name, snapshot_date,
             market_price, low_price, mid_price, high_price, direct_low_price
      FROM price_snapshots
      WHERE snapshot_date = '$DAY'
      ORDER BY product_id, sub_type_name
    ) TO STDOUT WITH CSV HEADER;" | gzip -9 > "$OUT"

  # Trust the file, not the pipe: gzip sits at the end of a pipeline whose
  # failures do not reach $?, so a psql that dies halfway still leaves a
  # well-formed short file. Counting the rows back is the only real check.
  BACK="$(( $(gzip -dc "$OUT" | wc -l | tr -d ' ') - 1 ))"
  [ "$BACK" -eq "$ROWS" ] \
    || { rm -f "$OUT"; die "$DAY: wrote $BACK rows, expected $ROWS -- aborted, file removed"; }

  git add "$OUT"
  git commit -q -m "Price snapshot for $DAY ($ROWS rows)"
  echo "  $DAY: $ROWS rows -> $OUT ($(du -h "$OUT" | cut -f1))"
  EXPORTED=$((EXPORTED + 1))
done <<< "$PLAN"

echo "Already present: $PRESENT. Exported: $EXPORTED. Skipped: $SKIPPED."
[ "$DRY_RUN" = "true" ] && exit 0
[ "$EXPORTED" -eq 0 ] && exit 0

if [ "$NO_PUSH" != "true" ]; then
  git push --quiet origin HEAD && echo "Pushed to $(git remote get-url origin)."
fi
