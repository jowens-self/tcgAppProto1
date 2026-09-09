import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { CardTrackerApi } from './api';
import { Holding, ItemSearchResult, ItemVariant } from './api.models';

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
  imports: [FormsModule, CurrencyPipe, DecimalPipe],
  templateUrl: './collection.html',
  styleUrl: './collection.css',
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

  protected readonly sortKey = signal<SortKey>('value');
  protected readonly sortAsc = signal(false);

  protected readonly visible = computed(() => {
    const key = this.sortKey();
    const dir = this.sortAsc() ? 1 : -1;
    return [...this.rows()].sort((a, b) => {
      switch (key) {
        case 'card':
          return dir * (a.name ?? '').localeCompare(b.name ?? '');
        case 'qty':
          return dir * (a.quantity - b.quantity);
        case 'gain':
          return dir * (nullLast(a.gain) - nullLast(b.gain));
        default:
          return dir * (nullLast(a.positionValue) - nullLast(b.positionValue));
      }
    });
  });

  /**
   * Totals skip nulls rather than treating them as zero, and the count of
   * what was skipped is shown beside them. A total that quietly omits your
   * unpriced cards while looking complete is worse than one that says so.
   */
  protected readonly totals = computed(() => {
    const rows = this.rows();
    let value = 0;
    let cost = 0;
    let unpriced = 0;
    let uncosted = 0;
    for (const r of rows) {
      if (r.positionValue === null) unpriced++;
      else value += r.positionValue;
      if (r.costBasis === null) uncosted++;
      else cost += r.costBasis * r.quantity;
    }
    return {
      value,
      cost,
      // Only meaningful across rows that have both sides, so it is derived
      // from the same subset rather than from the two totals above.
      gain: rows.reduce((sum, r) => sum + (r.gain ?? 0), 0),
      cards: rows.reduce((sum, r) => sum + r.quantity, 0),
      lines: rows.length,
      unpriced,
      uncosted,
    };
  });

  constructor() {
    this.load();

    this.typed
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => {
          this.searching.set(true);
          this.picked.set(null);
          return this.api.search(q, { limit: 12 });
        }),
      )
      .subscribe({
        next: (r) => {
          this.results.set(r);
          this.searching.set(false);
        },
        error: () => {
          this.searching.set(false);
          this.error.set('Search failed.');
        },
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
      error: () => this.error.set('Could not load this card&apos;s variants.'),
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
        },
        error: (e) => {
          this.saving.set(false);
          this.error.set(e?.error ?? 'Could not save that holding.');
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

  protected sortBy(key: SortKey): void {
    if (this.sortKey() === key) {
      this.sortAsc.update((v) => !v);
    } else {
      this.sortKey.set(key);
      this.sortAsc.set(key === 'card');
    }
  }

  protected ariaSort(key: SortKey): 'ascending' | 'descending' | null {
    if (this.sortKey() !== key) return null;
    return this.sortAsc() ? 'ascending' : 'descending';
  }

  /**
   * Cards with no image 404, which is ordinary: roughly 40% of Pokemon
   * singles have no counterpart upstream. Tracked and hidden rather than left
   * to the browser, which would otherwise paint a broken-image glyph in the
   * middle of a table of money. Same pattern as the search screen.
   */
  private readonly noImage = signal(new Set<number>());

  protected hasImage(productId: number): boolean {
    return !this.noImage().has(productId);
  }

  protected imageFailed(productId: number): void {
    this.noImage.update((s) => new Set(s).add(productId));
  }

  protected imageUrl(productId: number): string {
    return this.api.imageUrl(productId);
  }
}

/** Nulls sort last in either direction rather than reading as zero. */
function nullLast(v: number | null): number {
  return v === null ? Number.NEGATIVE_INFINITY : v;
}
