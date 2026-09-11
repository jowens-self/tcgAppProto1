import { CollectionPoint } from './api.models';

/**
 * The part of a value series worth drawing: from the first day anything was
 * held, onwards.
 *
 * collection_value() returns a row for EVERY day in the window, including the
 * days before the first card was recorded, each worth $0 with no positions.
 * Drawing those shows an empty collection as a chart of nothing, and a new one
 * as a long flat line at zero with a spike on the final pixel - both of which
 * read as a collection that lost its value rather than one that had not
 * started yet.
 *
 * Only LEADING empty days are dropped. A day with no positions after the first
 * holding is real - everything was sold - and the line falling to zero is the
 * truth, not padding.
 *
 * Decided on positions, not value: a card with no price that day is held but
 * shows as worth nothing, which is a gap to report, not a day before the
 * collection began.
 */
export function drawableSeries(points: readonly CollectionPoint[]): CollectionPoint[] {
  const first = points.findIndex((p) => p.positions > 0);
  return first === -1 ? [] : points.slice(first);
}
