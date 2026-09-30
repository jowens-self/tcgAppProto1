import { describe, expect, it } from 'vitest';
import { CollectionPoint } from './api.models';
import { drawableSeries } from './collection-series';

const pt = (date: string, value: number, positions: number, unpriced = 0): CollectionPoint =>
  ({ date, value, positions, unpriced, cost: 0, uncosted: 0 });

describe('drawableSeries', () => {
  it('draws nothing for an empty series', () => {
    expect(drawableSeries([])).toEqual([]);
  });

  /** The bug this exists for: every day present, nothing ever held. */
  it('draws nothing when nothing was held on any day in the window', () => {
    expect(drawableSeries([pt('2026-09-01', 0, 0), pt('2026-09-02', 0, 0)])).toEqual([]);
  });

  it('starts the line on the first day anything was held', () => {
    const s = [pt('2026-09-01', 0, 0), pt('2026-09-02', 0, 0), pt('2026-09-03', 40, 2), pt('2026-09-04', 45, 2)];
    expect(drawableSeries(s).map((p) => p.date)).toEqual(['2026-09-03', '2026-09-04']);
  });

  /** Selling everything is real history, not padding. */
  it('keeps empty days AFTER the collection began', () => {
    const s = [pt('2026-09-01', 30, 1), pt('2026-09-02', 0, 0), pt('2026-09-03', 0, 0)];
    expect(drawableSeries(s)).toHaveLength(3);
  });

  /** Held but unpriced is a gap to report, not a day before the start. */
  it('starts on a day that held cards even if none of them had a price', () => {
    const s = [pt('2026-09-01', 0, 0), pt('2026-09-02', 0, 3, 3), pt('2026-09-03', 50, 3)];
    expect(drawableSeries(s)[0].date).toBe('2026-09-02');
  });

  it('leaves a series that starts held untouched', () => {
    const s = [pt('2026-09-01', 10, 1), pt('2026-09-02', 12, 1)];
    expect(drawableSeries(s)).toEqual(s);
  });
});
