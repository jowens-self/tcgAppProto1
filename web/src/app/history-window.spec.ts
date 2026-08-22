import { describe, expect, it } from 'vitest';
import { HISTORY_WINDOWS, windowStart } from './history-window';

/**
 * Where the detail chart's range starts.
 *
 * The property actually worth pinning is that this reads its anchor from
 * the argument and never from a clock: every expectation below is a date
 * only derivable from the `latestDate` passed in, so an implementation that
 * counted back from today would fail all of them rather than pass by
 * accident on the day it was written.
 */
describe('windowStart', () => {
  it('counts back from the date given, not from today', () => {
    // Both ends inclusive: 2026-07-23 .. 2026-08-21 is thirty dated days.
    expect(windowStart('2026-08-21', 30)).toBe('2026-07-23');

    // An anchor well in the past must move the window with it. This is the
    // case a clock-based implementation gets wrong, and it is not
    // hypothetical: a card delisted last year has a latestDate from then.
    expect(windowStart('2024-06-30', 30)).toBe('2024-06-01');
  });

  it('crosses month and year boundaries', () => {
    expect(windowStart('2026-01-15', 30)).toBe('2025-12-17');
    expect(windowStart('2026-08-21', 365)).toBe('2025-08-22');
  });

  /**
   * Day arithmetic on a local-time Date can slip across a DST boundary,
   * which is why this runs on a UTC epoch. 2024 is a leap year, so a
   * 30-day window ending 2024-03-05 has to count 29 back through Feb 29.
   */
  it('counts leap days', () => {
    expect(windowStart('2024-03-05', 30)).toBe('2024-02-05');
    expect(windowStart('2024-03-01', 2)).toBe('2024-02-29');
  });

  it('treats a one-day window as the anchor itself', () => {
    expect(windowStart('2026-08-21', 1)).toBe('2026-08-21');
  });

  /**
   * latestDate comes straight from the API, so garbage here means the
   * contract changed. Refuse it rather than emitting a NaN date the server
   * would answer with an unrelated range.
   */
  it('refuses an unusable date', () => {
    expect(() => windowStart('21/08/2026', 30)).toThrow();
    expect(() => windowStart('', 30)).toThrow();
    expect(() => windowStart('2026-08-21', 0)).toThrow();
  });
});

describe('HISTORY_WINDOWS', () => {
  /** "All" is the no-filter case and the only one without a day count. */
  it('ends with All, the only null window', () => {
    const nulls = HISTORY_WINDOWS.filter((w) => w.days === null);
    expect(nulls).toHaveLength(1);
    expect(HISTORY_WINDOWS[HISTORY_WINDOWS.length - 1].days).toBeNull();
  });

  it('offers day counts Movers and Sets also offer', () => {
    // Shared vocabulary, so a range means the same thing on every screen.
    const moversWindows = [1, 7, 14, 30, 90, 180, 365];
    for (const w of HISTORY_WINDOWS) {
      if (w.days !== null) expect(moversWindows).toContain(w.days);
    }
  });
});
