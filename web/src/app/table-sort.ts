import { signal } from '@angular/core';

/**
 * Which column a table is sorted by, for headers that sort when clicked.
 *
 * Clicking the current column reverses it. A new column starts in its
 * natural order: A to Z for text, biggest first for numbers.
 */
export class TableSort<K extends string> {
  readonly key;
  readonly asc;

  constructor(
    initial: K,
    private readonly textKeys: readonly K[],
  ) {
    this.key = signal<K>(initial);
    this.asc = signal(textKeys.includes(initial));
  }

  /** 1 or -1, for multiplying a comparison by. */
  dir(): 1 | -1 {
    return this.asc() ? 1 : -1;
  }

  by(key: K): void {
    if (this.key() === key) {
      this.asc.update((v) => !v);
    } else {
      this.key.set(key);
      this.asc.set(this.textKeys.includes(key));
    }
  }

  aria(key: K): 'ascending' | 'descending' | null {
    if (this.key() !== key) return null;
    return this.asc() ? 'ascending' : 'descending';
  }
}

/**
 * Compares two numbers in the given direction, with a missing value last
 * either way. A null is "no answer", not zero, so it never sorts among the
 * real numbers, and never to the top of an ascending list.
 */
export function compareNullable(a: number | null, b: number | null, dir: 1 | -1): number {
  if (a === null || b === null) return a === b ? 0 : a === null ? 1 : -1;
  return dir * (a - b);
}
