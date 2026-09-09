import { describe, expect, it } from 'vitest';
import { Holding } from './api.models';
import { holdingTotals } from './holding-totals';

function h(over: Partial<Holding>): Holding {
  return {
    productId: 1,
    name: 'Card',
    cardNumber: null,
    setName: 'Set',
    game: 'Pokemon',
    subTypeName: 'Holofoil',
    condition: 'Near Mint',
    quantity: 1,
    costBasis: null,
    marketPrice: null,
    positionValue: null,
    gain: null,
    pricedOn: null,
    recordedOn: '2026-09-09',
    ...over,
  };
}

describe('holdingTotals', () => {
  it('is all zeroes for an empty collection', () => {
    expect(holdingTotals([])).toEqual({
      value: 0, cost: 0, gain: 0, cards: 0, lines: 0, unpriced: 0, uncosted: 0,
    });
  });

  it('multiplies cost by quantity, not by line', () => {
    const t = holdingTotals([h({ quantity: 3, costBasis: 10, positionValue: 60, gain: 30 })]);
    expect(t.cost).toBe(30);
    expect(t.value).toBe(60);
    expect(t.cards).toBe(3);
    expect(t.lines).toBe(1);
  });

  /**
   * The failure that would flatter: an unknown purchase price treated as 0
   * reports the whole market value as profit.
   */
  it('excludes an unknown cost rather than counting it as zero', () => {
    const t = holdingTotals([
      h({ quantity: 1, costBasis: null, positionValue: 100, gain: null }),
      h({ quantity: 1, costBasis: 40, positionValue: 100, gain: 60 }),
    ]);
    expect(t.cost).toBe(40);
    expect(t.gain).toBe(60);
    expect(t.uncosted).toBe(1);
  });

  /** The other direction: an unpriced card must not shrink the collection. */
  it('excludes an unpriced holding and says so', () => {
    const t = holdingTotals([
      h({ quantity: 2, costBasis: 5, positionValue: null, gain: null }),
      h({ quantity: 1, costBasis: 5, positionValue: 50, gain: 45 }),
    ]);
    expect(t.value).toBe(50);
    expect(t.unpriced).toBe(1);
    // Cost is still known for both, so it counts both.
    expect(t.cost).toBe(15);
  });

  it('counts cards across lines, and lines separately', () => {
    const t = holdingTotals([h({ quantity: 4 }), h({ quantity: 3 }), h({ quantity: 1 })]);
    expect(t.cards).toBe(8);
    expect(t.lines).toBe(3);
  });

  it('carries a loss through as a negative gain', () => {
    const t = holdingTotals([
      h({ quantity: 1, costBasis: 100, positionValue: 60, gain: -40 }),
      h({ quantity: 1, costBasis: 10, positionValue: 25, gain: 15 }),
    ]);
    expect(t.gain).toBe(-25);
  });
});
