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
SELECT 'price data is no more than 2 days stale: ' || c.name,
       COALESCE(MAX(ps.snapshot_date), DATE '1970-01-01')
           >= (CURRENT_DATE - 2) AS ok,
       CURRENT_DATE - COALESCE(MAX(ps.snapshot_date), DATE '1970-01-01') AS days_stale
  FROM tracked_categories tc
  JOIN categories c ON c.category_id = tc.category_id
  LEFT JOIN products p ON p.category_id = tc.category_id
  LEFT JOIN price_snapshots ps ON ps.product_id = p.product_id
 GROUP BY c.name
UNION ALL
-- A pull that starts and dies leaves a 'running' row forever. Distinct from
-- staleness: the data can still look recent while every new run is failing.
SELECT 'no ingest run has been running for over 6 hours',
       COUNT(*) = 0, COUNT(*)
  FROM ingest_runs
 WHERE status = 'running'
   AND started_at < now() - interval '6 hours';
