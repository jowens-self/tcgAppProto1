/**
 * How much of a card's price history the detail chart plots.
 * days: null means "All", everything we hold for that variant.
 */
export interface HistoryWindow {
  readonly label: string;
  readonly days: number | null;
}

/**
 * Same day counts Movers and Sets offer, minus 1 and 7.
 *
 * A window here is a range to draw, not two endpoints to compare. One day
 * is a single point and a week is six segments, and neither makes a useful
 * line at this width.
 */
export const HISTORY_WINDOWS: readonly HistoryWindow[] = [
  { label: '30d', days: 30 },
  { label: '90d', days: 90 },
  { label: '180d', days: 180 },
  { label: '1y', days: 365 },
  { label: 'All', days: null },
];

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const MS_PER_DAY = 86_400_000;

/**
 * Start date for a window of `days` ending on `latestDate`.
 *
 * Counts back from the date passed in, never from the current time. The
 * browser clock is host-local while every date in this database is UTC, and
 * pulls happen on demand, so the newest snapshot is often a day or two
 * behind today. Counting back from now would return 28 days of prices and
 * label them 30.
 *
 * Both ends are inclusive, so 30 days ending 2026-08-21 starts 2026-07-23.
 * The arithmetic runs on a UTC epoch so no DST day can shift the result.
 */
export function windowStart(latestDate: string, days: number): string {
  if (!ISO_DATE.test(latestDate)) {
    throw new Error(`windowStart: expected an ISO date, got "${latestDate}"`);
  }
  if (!Number.isInteger(days) || days < 1) {
    throw new Error(`windowStart: days must be a positive integer, got ${days}`);
  }
  const [year, month, day] = latestDate.split('-').map(Number);
  const anchor = Date.UTC(year, month - 1, day);
  return new Date(anchor - (days - 1) * MS_PER_DAY).toISOString().slice(0, 10);
}
