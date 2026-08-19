import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { CardTrackerApi } from './api';
import { Game, ItemSearchResult, ItemVariant, PricePoint } from './api.models';
import { PriceChart } from './price-chart';
import { variantLabelAddsInfo } from './variant-label';

/**
 * Card / Product Search - the first screen.
 *
 * Deliberately named "item" throughout, matching the API and the SQL
 * functions: sealed product is looked up exactly the same way a single is,
 * so nothing here says "card" when it means "any product."
 *
 * The flow mirrors how the data is actually shaped, rather than flattening
 * it: search finds a product, the product exposes its variants (which
 * genuinely differ in price), and a chosen variant has a history. Skipping
 * the variant step would mean picking a market for the user.
 */
@Component({
  selector: 'app-search',
  imports: [FormsModule, CurrencyPipe, DecimalPipe, PriceChart],
  templateUrl: './search.html',
  styleUrl: './search.css',
})
export class Search {
  private readonly api = inject(CardTrackerApi);

  /**
   * Templates can only call class members, so the shared rule is re-exposed
   * here rather than reimplemented - search and any future screen showing
   * labels must agree on when one is worth showing.
   */
  protected readonly variantLabelAddsInfo = variantLabelAddsInfo;

  protected readonly games = signal<Game[]>([]);
  protected readonly results = signal<ItemSearchResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly selected = signal<ItemSearchResult | null>(null);
  protected readonly variants = signal<ItemVariant[]>([]);
  protected readonly activeVariant = signal<string | null>(null);
  protected readonly history = signal<PricePoint[]>([]);
  protected readonly loadingDetail = signal(false);

  /**
   * product_ids whose thumbnail 404'd. Roughly 40% of Pokemon singles have
   * no upstream image and One Piece has none at all, so this is the normal
   * path, not error handling - we swap in a placeholder rather than leaving
   * a broken image icon.
   */
  protected readonly noImage = signal<ReadonlySet<number>>(new Set());

  /**
   * product_ids whose thumbnail has finished loading. The <img> stays
   * invisible until it lands: relying on (error) alone still lets the
   * browser paint its broken-image glyph for a frame or two before the
   * placeholder swaps in, which looks like a bug on every row we have no
   * image for - and that is ~40% of them.
   */
  protected readonly loadedImages = signal<ReadonlySet<number>>(new Set());

  protected query = '';
  protected game: number | null = null;
  protected hideSealed = false;

  /**
   * Typing pushes into this subject rather than firing a request per
   * keystroke. debounceTime waits for a pause; switchMap cancels an
   * in-flight request when a newer one starts, which is what stops slow
   * early results from arriving after (and overwriting) fast later ones.
   */
  private readonly typed = new Subject<string>();

  constructor() {
    this.api.games().subscribe({
      next: (g) => this.games.set(g),
      error: () => this.error.set('Could not reach the API. Is ApiServer running on :7070?'),
    });

    this.typed
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => {
          this.searching.set(true);
          this.error.set(null);
          return this.api.search(q, {
            game: this.game,
            includeSealed: !this.hideSealed,
            limit: 50,
          });
        }),
      )
      .subscribe({
        next: (r) => {
          this.results.set(r);
          this.searching.set(false);
          this.searched.set(true);
        },
        error: () => {
          this.searching.set(false);
          this.searched.set(true);
          this.error.set('Search failed. Is ApiServer running on :7070?');
        },
      });
  }

  protected imageUrl(productId: number): string {
    return this.api.imageUrl(productId);
  }

  protected imageFailed(productId: number): void {
    this.noImage.update((s) => new Set(s).add(productId));
  }

  protected imageLoaded(productId: number): void {
    this.loadedImages.update((s) => new Set(s).add(productId));
  }

  protected onQueryChange(value: string): void {
    this.query = value;
    const trimmed = value.trim();
    if (!trimmed) {
      // The API rejects an empty q with 400, so don't ask.
      this.results.set([]);
      this.searched.set(false);
      this.clearSelection();
      return;
    }
    this.typed.next(trimmed);
  }

  /** Filters change what the current query means, so re-run it. */
  protected onFilterChange(): void {
    const trimmed = this.query.trim();
    if (trimmed) {
      this.results.set([]);
      // distinctUntilChanged would swallow an identical query string, so
      // go straight to the API instead of pushing through the subject.
      this.searching.set(true);
      this.api
        .search(trimmed, { game: this.game, includeSealed: !this.hideSealed, limit: 50 })
        .subscribe({
          next: (r) => {
            this.results.set(r);
            this.searching.set(false);
            this.searched.set(true);
          },
          error: () => {
            this.searching.set(false);
            this.error.set('Search failed.');
          },
        });
    }
    this.clearSelection();
  }

  protected select(item: ItemSearchResult): void {
    this.selected.set(item);
    this.variants.set([]);
    this.history.set([]);
    this.activeVariant.set(null);
    this.loadingDetail.set(true);

    this.api.variants(item.productId).subscribe({
      next: (v) => {
        this.variants.set(v);
        this.loadingDetail.set(false);
        // Open on the most valuable variant - for a card with a $5 Normal
        // and a $77 Reverse Holofoil, that's overwhelmingly the one being
        // looked up.
        const best = [...v].sort(
          (a, b) => (b.latestMarket ?? 0) - (a.latestMarket ?? 0),
        )[0];
        if (best) this.showVariant(item.productId, best.subTypeName);
      },
      error: () => {
        this.loadingDetail.set(false);
        this.error.set('Could not load variants for this item.');
      },
    });
  }

  protected showVariant(productId: number, subType: string): void {
    this.activeVariant.set(subType);
    this.history.set([]);
    this.api.history(productId, subType).subscribe({
      next: (h) => this.history.set(h),
      error: () => this.error.set(`No history for ${subType}.`),
    });
  }

  protected clearSelection(): void {
    this.selected.set(null);
    this.variants.set([]);
    this.history.set([]);
    this.activeVariant.set(null);
  }

  /** Percent change across the whole loaded series, or null if unusable. */
  protected totalReturn(): number | null {
    const pts = this.history().filter((p) => p.trendablePrice != null);
    if (pts.length < 2) return null;
    const start = pts[0].trendablePrice!;
    const end = pts[pts.length - 1].trendablePrice!;
    // Matches the server rule: no percent change off a zero/negative start.
    if (start <= 0) return null;
    return ((end - start) / start) * 100;
  }
}
