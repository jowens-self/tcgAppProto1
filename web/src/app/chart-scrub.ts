/**
 * Which data point sits under a cursor at `fraction` across the chart's width.
 *
 * Chart points are spaced evenly across the full width (x = i / (n-1) * W), so
 * the one under the cursor is a rounding rather than a search: O(1) whether
 * the chart holds 30 days or 900, and no lookup structure to keep in step.
 *
 * `fraction` is measured against the RENDERED box, not the SVG viewBox. The
 * chart draws with preserveAspectRatio="none", so its internal x units are
 * stretched and do not match screen pixels - but the proportion along the
 * width survives the stretch, which is why it is the thing passed in.
 *
 * Out-of-range input clamps rather than returning nothing. A pointer dragged
 * past the edge should read the first or last day, not blank the label.
 */
export function scrubIndex(fraction: number, pointCount: number): number | null {
  if (!Number.isFinite(fraction) || pointCount < 2) return null;
  const clamped = Math.min(1, Math.max(0, fraction));
  return Math.round(clamped * (pointCount - 1));
}
