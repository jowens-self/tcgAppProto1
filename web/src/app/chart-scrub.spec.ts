import { describe, expect, it } from 'vitest';
import { scrubIndex } from './chart-scrub';

describe('scrubIndex', () => {
  it('maps the ends to the first and last point', () => {
    expect(scrubIndex(0, 30)).toBe(0);
    expect(scrubIndex(1, 30)).toBe(29);
  });

  it('maps the middle to the middle', () => {
    expect(scrubIndex(0.5, 101)).toBe(50);
  });

  /** Dragging past the edge should read the edge, not blank the label. */
  it('clamps out-of-range positions instead of going out of bounds', () => {
    expect(scrubIndex(-3, 30)).toBe(0);
    expect(scrubIndex(4, 30)).toBe(29);
  });

  it('rounds to the nearest point rather than truncating', () => {
    // 11 points, so each covers a tenth. 0.34 is nearest the 3rd index.
    expect(scrubIndex(0.34, 11)).toBe(3);
    expect(scrubIndex(0.36, 11)).toBe(4);
  });

  it('has nothing to point at below two points', () => {
    expect(scrubIndex(0.5, 1)).toBeNull();
    expect(scrubIndex(0.5, 0)).toBeNull();
  });

  /** A zero-width box divides by zero upstream and arrives as NaN. */
  it('returns null for a non-finite position', () => {
    expect(scrubIndex(NaN, 30)).toBeNull();
    expect(scrubIndex(Infinity, 30)).toBeNull();
  });
});
