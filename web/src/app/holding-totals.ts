import { Holding } from './api.models';

export interface CollectionTotals {
  value: number;
  cost: number;
  gain: number;
  cards: number;
  lines: number;
  /** Holdings with no price on the chosen date, so absent from `value`. */
  unpriced: number;
  /** Holdings with no purchase price, so absent from `cost` and `gain`. */
  uncosted: number;
}

/**
 * Sum a collection, skipping what it cannot know rather than calling it zero.
 *
 * The distinction is the whole point. A card with no recorded purchase price
 * counted as costing 0 would report its entire market value as profit, and a
 * card with no price on the chosen date counted as worth 0 would quietly
 * shrink the collection. Both are wrong in the direction that flatters, which
 * is the worst direction for a number someone might act on.
 *
 * So the skipped rows are counted and returned. A total that silently omits
 * rows while looking complete is worse than one that says what it left out.
 *
 * Its own function, and tested, because this is the money math: every figure
 * on the screen comes from here.
 */
export function holdingTotals(rows: readonly Holding[]): CollectionTotals {
  let value = 0;
  let cost = 0;
  let gain = 0;
  let cards = 0;
  let unpriced = 0;
  let uncosted = 0;

  for (const r of rows) {
    cards += r.quantity;
    if (r.positionValue === null) unpriced++;
    else value += r.positionValue;

    if (r.costBasis === null) uncosted++;
    else cost += r.costBasis * r.quantity;

    // Server-side gain is already null unless BOTH sides are known, so this
    // never mixes a known price with an unknown cost.
    if (r.gain !== null) gain += r.gain;
  }

  return { value, cost, gain, cards, lines: rows.length, unpriced, uncosted };
}
