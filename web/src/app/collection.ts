import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { CardTrackerApi } from './api';
import {
  ChartDatum,
  CollectionPoint,
  Holding,
  HoldingEvent,
  ItemSearchResult,
  ItemVariant,
} from './api.models';
import { CardThumb } from './card-thumb';
import { PriceChart } from './price-chart';
import { drawableSeries } from './collection-series';
import { holdingTotals } from './holding-totals';
import { TableSort, compareNullable } from './table-sort';
import { UserPrefs } from './user-prefs';

/** Columns the table can be ordered by. */
type SortKey = 'card' | 'qty' | 'value' | 'gain';

/**
 * Your collection: what you own, what it is worth, what it cost.
 *
 * Adding is deliberately search-then-pick rather than a free-text box. A
 * holding is keyed by (product, variant), and a variant the card does not
 * have can never be priced - it would sit in the collection at null value
 * forever. Picking from the card's real variants makes that unrepresentable
 * here, and the server refuses it anyway as the backstop.
 *
 * "Value on" is the reason holdings are stored append-only. It moves the
 * holdings AND the prices to that date, so the answer is what the collection
 * was really worth then, not today's cards at old prices - which is the
 * answer most trackers give, because it is the only one they can give.
 */
@Component({
  selector: 'app-collection',
  imports: [FormsModule, CurrencyPipe, DecimalPipe, CardThumb, PriceChart],
  templateUrl: './collection.html',
  styleUrl: './collection.css',
  host: { class: 'page' },
})
export class Collection {
  private readonly api = inject(CardTrackerApi);

  /** TCGplayer's condition ladder, so entries match what a seller reads on their own listings. */
  protected readonly conditions = [
    'Near Mint',
    'Lightly Played',
    'Moderately Played',
    'Heavily Played',
    'Damaged',
  ];

  protected readonly rows = signal<Holding[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  /** Empty = today. Bound to a date input, so the format is already YYYY-MM-DD. */
  protected asOf = '';

  // --- the add form ---
  protected query = '';
  protected readonly results = signal<ItemSearchResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly picked = signal<ItemSearchResult | null>(null);
  protected readonly variants = signal<ItemVariant[]>([]);
  protected variant = '';
  protected condition = 'Near Mint';
  protected quantity = 1;
  protected costBasis: number | null = null;

  protected readonly sort = new TableSort<SortKey>('value', ['card']);

  protected readonly visible = computed(() => {
    const key = this.sort.key();
    const dir = this.sort.dir();
    return [...this.rows()].sort((a, b) => {
      switch (key) {
        case 'card':
          return dir * (a.name ?? '').localeCompare(b.name ?? '');
        case 'qty':
          return dir * (a.quantity - b.quantity);
        case 'gain':
          return compareNullable(a.gain, b.gain, dir);
        default:
          return compareNullable(a.positionValue, b.positionValue, dir);
      }
    });
  });

  /**
   * Extracted and tested in holding-totals.ts: it is the money math, and
   * every figure on the screen comes from it.
   */
  protected readonly totals = computed(() => holdingTotals(this.rows()));

  constructor() {
    this.load();
    this.loadChart();

    this.typed
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => {
          this.searching.set(true);
          this.picked.set(null);
          return this.api.search(q, { limit: 12 }).pipe(
            // Caught per request: an error reaching subscribe() would end the
            // stream, and the box would ignore every keystroke after it.
            catchError(() => {
              this.error.set('Search failed.');
              return of([]);
            }),
          );
        }),
      )
      .subscribe((r) => {
        this.results.set(r);
        this.searching.set(false);
      });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.holdings(this.asOf || undefined).subscribe({
      next: (r) => {
        this.rows.set(r);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not reach the API. Is ApiServer running on :7070?');
      },
    });
  }

  /**
   * Searches as you type, matching the Search screen. switchMap cancels the
   * in-flight request when a newer one starts, so a slow early result cannot
   * land after (and overwrite) a fast later one.
   */
  private readonly typed = new Subject<string>();

  protected onQueryChange(value: string): void {
    this.query = value;
    const trimmed = value.trim();
    if (!trimmed) {
      // The API rejects an empty q with 400, so don't ask.
      this.results.set([]);
      return;
    }
    this.typed.next(trimmed);
  }

  /**
   * Picking a card loads its real variants. Preselecting the only one when
   * there is only one saves a pointless choice; leaving it blank when there
   * are several forces the decision, because guessing it is exactly the
   * mistake this screen exists to prevent.
   */
  protected pick(r: ItemSearchResult): void {
    this.picked.set(r);
    this.variants.set([]);
    this.variant = '';
    this.api.variants(r.productId).subscribe({
      next: (v) => {
        this.variants.set(v);
        if (v.length === 1) this.variant = v[0].subTypeName;
      },
      error: () => this.error.set("Could not load this card's variants."),
    });
  }

  protected add(): void {
    const card = this.picked();
    if (!card || !this.variant || this.quantity < 0) return;
    this.saving.set(true);
    this.error.set(null);
    this.api
      .recordHolding({
        productId: card.productId,
        subTypeName: this.variant,
        condition: this.condition,
        quantity: this.quantity,
        costBasis: this.costBasis,
      })
      .subscribe({
        next: (all) => {
          this.rows.set(all);
          this.saving.set(false);
          this.reset();
          // The line just changed shape; leaving it stale would show a chart
          // that disagrees with the table directly beneath it.
          this.loadChart();
        },
        error: (e) => {
          this.saving.set(false);
          // The API's refusal says what was wrong; show it, not a generic line.
          this.error.set(e?.error?.title ?? 'Could not save that holding.');
        },
      });
  }

  /**
   * Selling is a quantity of 0, not a delete. The row stays in the log, so
   * "what did I hold in June" keeps working after you sell in July.
   */
  protected sell(r: Holding): void {
    this.saving.set(true);
    this.api
      .recordHolding({
        productId: r.productId,
        subTypeName: r.subTypeName,
        condition: r.condition,
        quantity: 0,
      })
      .subscribe({
        next: (all) => {
          this.rows.set(all);
          this.saving.set(false);
          this.loadChart();
        },
        error: () => {
          this.saving.set(false);
          this.error.set('Could not record that sale.');
        },
      });
  }

  protected reset(): void {
    this.picked.set(null);
    this.results.set([]);
    this.variants.set([]);
    this.query = '';
    this.variant = '';
    this.quantity = 1;
    this.costBasis = null;
  }

  // --- value over time ---
  //
  // Loaded separately from the table, not with it. This query costs roughly
  // ten times what the holdings list does, and blocking the numbers someone
  // came to read on a picture they did not ask for first is the wrong trade.
  protected readonly valueSeries = signal<CollectionPoint[]>([]);
  protected readonly chartLoading = signal(false);
  protected chartDays = 90;

  protected readonly chartWindows = [
    { days: 30, label: '30d' },
    { days: 90, label: '90d' },
    { days: 180, label: '180d' },
    { days: 365, label: '1y' },
  ];

  /** The window from the first day anything was held; see drawableSeries(). */
  private readonly drawn = computed(() => drawableSeries(this.valueSeries()));

  /**
   * Whether there is anything to draw. Not valueSeries().length: the server
   * returns a row for every day in the window whether or not anything was
   * held, so the length is always the window and says nothing about content.
   */
  protected readonly hasHistory = computed(() => this.drawn().length > 0);

  protected readonly chartSeries = computed<ChartDatum[]>(() =>
    this.drawn().map((p) => ({ date: p.date, value: p.value })),
  );

  /**
   * What was paid for the cards held each day, drawn under the value when
   * that's switched on in Settings. Null when it's off, which draws nothing.
   */
  private readonly prefs = inject(UserPrefs);
  protected readonly costSeries = computed<ChartDatum[] | null>(() =>
    this.prefs.showCostLine()
      ? this.drawn().map((p) => ({ date: p.date, value: p.cost }))
      : null,
  );

  /**
   * Holdings on the chart's last day with no purchase price. They're in the
   * value line and not the paid one, so the gap between the two overstates
   * the gain by their value, and the note under the chart says so.
   */
  protected readonly uncostedNow = computed(() => this.drawn().at(-1)?.uncosted ?? 0);

  /**
   * Days where at least one held card had no price. Their value is missing
   * from the line rather than wrong, and saying so beats letting a dip look
   * like the market.
   */
  protected readonly chartGaps = computed(
    () => this.drawn().filter((p) => p.unpriced > 0).length,
  );

  protected loadChart(): void {
    this.chartLoading.set(true);
    this.api.collectionValue(this.chartDays).subscribe({
      next: (v) => {
        this.valueSeries.set(v);
        this.chartLoading.set(false);
      },
      // Silent: the table above already reports an unreachable API, and a
      // second alarm for one cause teaches people to ignore alarms.
      error: () => this.chartLoading.set(false),
    });
  }

  // --- per-row history ---
  //
  // Collapsed by default and fetched on demand: most rows are never expanded,
  // and a request per row on load would be one query per card to show
  // something nobody asked to see.
  protected readonly expanded = signal<number | null>(null);
  protected readonly events = signal<HoldingEvent[]>([]);

  protected toggleHistory(r: Holding): void {
    if (this.expanded() === r.productId) {
      this.expanded.set(null);
      return;
    }
    this.expanded.set(r.productId);
    this.events.set([]);
    this.api.holdingHistory(r.productId).subscribe({
      next: (e) => this.events.set(e),
      error: () => this.error.set("Could not load that card's history."),
    });
  }
}
