/**
 * How much of a card's price history the detail chart plots.
 *
 * `days: null` means "All" - everything we hold for that variant, which is
 * what the chart always showed before a range could be picked.
 */
export interface HistoryWindow {
  readonly label: string;
  readonly days: number | null;
}

/**
 * The same vocabulary Movers and Sets use (CLAUDE.md: 1 / 7 / 14 / 30 / 90 /
 * 180 days / 1 year), minus the two shortest.
 *
 * 1 and 7 are dropped because a window here is a range to *draw*, not a
 * two-point comparison: one day is a single dot, and a week is six segments
 * that read as noise at this width. Movers keeps them because it compares
 * two smoothed endpoints, which a week supports and a line chart doesn't.
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
 * The `from` date for a window of `days` ending on `latestDate`.
 *
 * **Anchored on the data, never on a clock.** The obvious implementation
 * counts back from `new Date()`, and that is wrong twice over. The browser's
 * clock is host-local while every date in this database is UTC - the same
 * confusion that put a day-old date on a pull once already, and that
 * CLAUDE.md answers with "where a clock IS wanted, it is always
 * ZoneOffset.UTC." And pulls are on demand, so the newest snapshot is
 * routinely a day or two behind today with nothing at all wrong; counting
 * back from today would quietly hand back a 28-day window and call it 30.
 * Anchoring on the variant's own `latestDate` makes "30 days" mean thirty
 * days of prices.
 *
 * Both ends are inclusive: 30 days ending 2026-08-21 starts 2026-07-23, so
 * the server returns thirty dated rows rather than thirty-one.
 *
 * Arithmetic runs on a UTC epoch on purpose - `Date.UTC` has no DST, so
 * subtracting days can never land on a 23- or 25-hour day and slip a date.
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
