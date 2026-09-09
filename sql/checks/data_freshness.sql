-- Is data still arriving?
--
-- Part 40: the hourly job failed silently for five days and nobody noticed,
-- because a launchd job that cannot reach its own repository exits without
-- writing anything, and an empty log looks exactly like a quiet success. The
-- conclusion recorded there was that a job whose failure is indistinguishable
-- from its success needs a check that reads the DATA rather than the exit
-- status. This is that check.
--
-- Two days, not one. The pull anchors snapshot_date to the mirror's own
-- dataAsOf(), which is normally yesterday, so a healthy system is one day
-- behind by design and a one-day threshold would cry wolf every morning.
-- Two days means a pull was genuinely missed. On the 2026-08-30 outage this
-- would have fired on 09-01, three days before it was actually found.
--
-- Per tracked category, not globally: one game silently stopping while another
-- keeps arriving is the failure a global MAX() would hide completely.
-- Bounded to four days on purpose. Grouping the whole of price_snapshots by
-- category aggregates 42M rows and measured 22.5 seconds; the bounded lookup
-- reads idx_snapshots_date and takes 250ms. The unbounded fallback runs only
-- when the narrow window found nothing, i.e. only when data really has
-- stopped. Same trick item_movers() documents.
SELECT 'price data is no more than 2 days stale: ' || c.name,
       COALESCE(f.newest, DATE '1970-01-01') >= (CURRENT_DATE - 2) AS ok,
       CURRENT_DATE - COALESCE(f.newest, DATE '1970-01-01') AS days_stale
  FROM tracked_categories tc
  JOIN categories c ON c.category_id = tc.category_id
  CROSS JOIN LATERAL (
      SELECT COALESCE(
          (SELECT MAX(ps.snapshot_date) FROM price_snapshots ps
             JOIN products p ON p.product_id = ps.product_id
            WHERE p.category_id = c.category_id
              AND ps.snapshot_date >= CURRENT_DATE - 4),
          (SELECT MAX(ps.snapshot_date) FROM price_snapshots ps
             JOIN products p ON p.product_id = ps.product_id
            WHERE p.category_id = c.category_id)
      ) AS newest
  ) f
UNION ALL
-- A pull that starts and dies leaves a 'running' row forever. Distinct from
-- staleness: the data can still look recent while every new run is failing.
SELECT 'no ingest run has been running for over 6 hours',
       COUNT(*) = 0, COUNT(*)
  FROM ingest_runs
 WHERE status = 'running'
   AND started_at < now() - interval '6 hours';
