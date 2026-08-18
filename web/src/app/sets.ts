import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CardTrackerApi } from './api';
import { Game, SetOverview } from './api.models';

/** Columns the table can be ordered by. */
type SortKey = 'set' | 'value' | 'change' | 'cards' | 'concentration';

/**
 * Set overview - "what is each set worth, and how did it move."
 *
 * The screen the set_value()/set_return() primitives were built for (Part
 * 14), finally exposed. Sorting and the empty-row filter are client-side on
 * purpose: ~300 sets arrive in one response, so both are free and cost no
 * round trip. Add a server-side ORDER BY only if this ever paginates.
 *
 * Note which state is a signal and which is a plain field. Anything `visible`
 * reads must be a signal or the computed won't recompute when it changes;
 * `game` and `days` are plain fields because changing them refetches, which
 * updates `rows` anyway.
 *
 * On CONCENTRATION, which is why this screen shows more than a percentage.
 * Measured 2026-08-18 against real data: among Pokemon sets with a >=2%
 * 30-day move, the biggest single contributing row is routinely 50-87% of
 * all movement in the set. Base Set (Shadowless) read +59.96% and $9,750 of
 * its $10,718 move was one card whose market price toggles between ~$250
 * and exactly $10,000 for weeks at a time. Showing the percentage alone
 * would let a one-card artifact read as a set-wide verdict, so the biggest
 * contributor is shown beside it and flagged past a threshold.
 */
@Component({
  selector: 'app-sets',
  imports: [FormsModule, CurrencyPipe, DecimalPipe],
  templateUrl: './sets.html',
  styleUrl: './sets.css',
})
export class Sets {
  private readonly api = inject(CardTrackerApi);

  /**
   * Past this share of a set's gross movement, one row is doing enough of
   * the work that the set-level number shouldn't be read as broad. 50% is a
   * deliberate round number, not a fitted one: it's the point where a
   * single card outweighs every other card in the set combined.
   */
  private static readonly CONCENTRATION_FLAG = 50;

  protected readonly games = signal<Game[]>([]);
  protected readonly rows = signal<SetOverview[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Refetch on change, so these need not be signals. */
  protected game: number | null = null;
  protected days = 30;

  /**
   * Sets with no priced basket have a value but no return. Hidden by
   * default because a column of blanks buries the sets that do have an
   * answer - but they are real sets, so it is a toggle rather than a filter
   * baked into the query.
   */
  protected readonly hideEmpty = signal(true);

  /**
   * Replace readings that swung hard and reverted the next day with the
   * median of their neighbours. On by default (the server's default too).
   * Exposed as a toggle rather than hidden because it CHANGES REPORTED
   * NUMBERS - on 2026-08-04 it moved EX Dragon Frontiers from -20.91% to
   * +8.43% - and a correction you cannot switch off is a correction you
   * cannot check.
   */
  protected readonly despike = signal(true);
  protected readonly sortKey = signal<SortKey>('value');
  protected readonly sortAsc = signal(false);

  protected readonly visible = computed(() => {
    const list = this.hideEmpty()
      ? this.rows().filter((r) => r.pctReturn !== null)
      : this.rows();
    const key = this.sortKey();
    const dir = this.sortAsc() ? 1 : -1;

    return [...list].sort((a, b) => {
      switch (key) {
        case 'set':
          return dir * a.setName.localeCompare(b.setName);
        case 'cards':
          return dir * (a.nPriced - b.nPriced);
        case 'change':
          return dir * (nullLast(a.pctReturn) - nullLast(b.pctReturn));
        case 'concentration':
          return dir * (nullLast(a.topShare) - nullLast(b.topShare));
        default:
          return dir * (nullLast(a.totalValue) - nullLast(b.totalValue));
      }
    });
  });

  /** The window the server actually resolved, echoed back on every row. */
  protected readonly window = computed(() => {
    const first = this.rows()[0];
    return first ? { from: first.startDate, to: first.endDate } : null;
  });

  /** How many of the visible sets are carried by a single card. */
  protected readonly flaggedCount = computed(
    () => this.visible().filter((r) => this.concentrated(r)).length,
  );

  /** How many visible sets had at least one reading replaced. */
  protected readonly despikedCount = computed(
    () => this.visible().filter((r) => r.nDespiked > 0).length,
  );

  constructor() {
    this.api.games().subscribe({
      next: (g) => {
        this.games.set(g);
        this.load();
      },
      error: () => this.error.set('Could not reach the API. Is ApiServer running on :7070?'),
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .setOverview({ game: this.game, days: this.days, despike: this.despike() })
      .subscribe({
        next: (r) => {
          this.rows.set(r);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Could not load the set overview.');
        },
      });
  }

  /**
   * Why a row's numbers were adjusted, spelled out per row rather than left
   * to a legend - the tooltip is where someone checks a number they don't
   * believe.
   */
  protected despikeTitle(r: SetOverview): string {
    const n = r.nDespiked;
    const noun = n === 1 ? 'reading' : 'readings';
    return (
      `${n} ${noun} in this set swung hard and returned to the previous level ` +
      `the next day, so the median of the neighbouring days was used instead ` +
      `(total adjustment $${r.despikeUsd ?? 0}). Stored prices are unchanged. ` +
      `Untick "Correct one-day price errors" to see the raw comparison.`
    );
  }

  /** Click a header to sort by it; click again to reverse. */
  protected sortBy(key: SortKey): void {
    if (this.sortKey() === key) {
      this.sortAsc.update((v) => !v);
    } else {
      this.sortKey.set(key);
      // Text reads naturally A->Z; numbers read naturally biggest-first.
      this.sortAsc.set(key === 'set');
    }
  }

  protected ariaSort(key: SortKey): 'ascending' | 'descending' | null {
    if (this.sortKey() !== key) return null;
    return this.sortAsc() ? 'ascending' : 'descending';
  }

  /** True when one row drives enough of the move to distrust the total. */
  protected concentrated(r: SetOverview): boolean {
    return r.topShare !== null && r.topShare >= Sets.CONCENTRATION_FLAG;
  }

  /**
   * Cards priced on the end date but not at the window's start. Their value
   * counts toward totalValue and NOT toward the return, which is exactly
   * why both numbers are shown rather than one.
   */
  protected notInBasket(r: SetOverview): number {
    return r.nPriced - r.nCommon;
  }
}

/** Nulls sort last in either direction rather than reading as zero. */
function nullLast(v: number | null): number {
  return v === null ? Number.NEGATIVE_INFINITY : v;
}
