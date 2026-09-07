#!/bin/sh
# Quick "what's actually running / how far along is it" check.
cd "/Users/skullllbunnies/Claude/tcgAppProto1" || exit 1

echo "== processes =="
for pat in "ImportCardImages:image import" "com.cardtracker.api.ApiServer:API" "cardtracker-0.1.0.jar$:price pull" "ng serve:web dev server"; do
  p=${pat%%:*}; label=${pat#*:}
  pids=$(pgrep -f "$p" 2>/dev/null | tr '\n' ' ')
  if [ -n "$pids" ]; then
    n=$(echo "$pids" | wc -w | tr -d ' ')
    echo "  $label: RUNNING (pid $pids)$( [ "$n" -gt 1 ] && echo '  <-- MORE THAN ONE' )"
  else
    echo "  $label: stopped"
  fi
done

echo "== image import progress =="
docker exec cardtracker-db psql -U cardtracker -d cardtracker -t -A -c "
SELECT '  '||status||': '||COUNT(*) FROM card_images GROUP BY status
UNION ALL SELECT '  remaining: '||COUNT(*) FROM products p
  LEFT JOIN card_images ci ON ci.product_id=p.product_id
  WHERE p.category_id=3 AND p.is_sealed=FALSE
    AND (ci.product_id IS NULL OR ci.status='failed');" 2>/dev/null || echo "  (postgres not reachable)"
echo "  on disk: $(find images -type f 2>/dev/null | wc -l | tr -d ' ') files, $(du -sh images 2>/dev/null | awk '{print $1}')"

echo "== data freshness =="
docker exec cardtracker-db psql -U cardtracker -d cardtracker -t -A -c "
SELECT '  newest prices: '||MAX(snapshot_date) FROM price_snapshots;" 2>/dev/null
