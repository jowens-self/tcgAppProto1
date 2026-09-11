import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { CardTrackerApi } from './api';
import {
  Game,
  ImageProvenance,
  ItemSearchResult,
  ItemVariant,
  PricePoint,
  ChartDatum,
} from './api.models';
import { HISTORY_WINDOWS, HistoryWindow, windowStart } from './history-window';
import { PriceChart } from './price-chart';
import { variantLabelAddsInfo } from './variant-label';

/**
 * Card / Product Search, the first screen.
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
   * Rows per request, and the page size "Load more" advances by.
   *
   * 50 rather than the API's 200 ceiling because every row fetches its own
   * thumbnail and every request opens its own JDBC connection, so a page is
   * ~50 concurrent connections against Postgres' max_connections of 100.
   * Measured 2026-08-20: 200 concurrent all succeed, 300 mixed produce 50
   * `too many clients` failures. Paging keeps each page inside that, where
   * simply raising the limit would walk toward it.
   */
  private static readonly PAGE = 50;

  /**
   * Templates can only call class members, so the shared rule is re-exposed
   * here rather than reimplemented, so search and any future screen showing
   * labels must agree on when one is worth showing.
   */
  protected readonly variantLabelAddsInfo = variantLabelAddsInfo;

  protected readonly games = signal<Game[]>([]);
  protected readonly results = signal<ItemSearchResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Whether the last page came back full, which is the only evidence we have
   * that more exist, since the endpoint returns rows rather than a total.
   * A short page ends the list and the button disappears. A page that is
   * exactly full but happens to be the last costs one extra request to find
   * that out, which beats claiming a count the server never gave us.
   */
  protected readonly moreAvailable = signal(false);
  protected readonly loadingMore = signal(false);

  /**
   * Bumped every time a new result list is established. "Load more"
   * captures it and drops its answer if it has moved. Otherwise a page
   * still in flight when a new search lands would append the old query's
   * rows to the new query's list.
   */
  private generation = 0;

  protected readonly selected = signal<ItemSearchResult | null>(null);
  protected readonly variants = signal<ItemVariant[]>([]);
  protected readonly activeVariant = signal<string | null>(null);
  protected readonly history = signal<PricePoint[]>([]);

  /** The chart takes a plain {date, value} series; this is the price view of it. */
  protected readonly chartSeries = computed<ChartDatum[]>(() =>
    this.history().map((p) => ({ date: p.snapshotDate, value: p.trendablePrice })),
  );
  protected readonly loadingDetail = signal(false);

  /**
   * How much of the chosen variant's history the chart plots.
   *
   * Opens on "All" because that is what this screen has always shown, and
   * the full series is what the database actually holds (925 days of it).
   * The shorter ranges are one click away, and the choice is sticky across
   * cards, the way Movers keeps its own window between loads.
   */
  protected readonly chartWindows = HISTORY_WINDOWS;
  protected readonly chartWindow = signal<HistoryWindow>(
    HISTORY_WINDOWS[HISTORY_WINDOWS.length - 1],
  );


  /**
   * Provenance of the selected card's picture, or null when we hold no image
   * or have not heard back yet. Only 'base_art' renders anything; see the
   * band in search.html.
   */
  protected readonly provenance = signal<ImageProvenance | null>(null);

  /**
   * product_ids whose thumbnail 404'd. Roughly 40% of Pokemon singles have
   * no upstream image and One Piece has none at all, so this is the normal
   * path rather than error handling, so we swap in a placeholder instead
   * of leaving a broken image icon.
   */
  protected readonly noImage = signal<ReadonlySet<number>>(new Set());

  /**
   * product_ids whose thumbnail has finished loading. The <img> stays
   * invisible until it lands: relying on (error) alone still lets the
   * browser paint its broken-image glyph for a frame or two before the
   * placeholder swaps in, which looks like a bug on every row we have no
   * image for, and that is about 40% of them.
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
            limit: Search.PAGE,
          });
        }),
      )
      .subscribe({
        next: (r) => {
          this.setResults(r);
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
      this.setResults([]);
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
      this.setResults([]);
      // distinctUntilChanged would swallow an identical query string, so
      // go straight to the API instead of pushing through the subject.
      this.searching.set(true);
      this.api
        .search(trimmed, {
          game: this.game,
          includeSealed: !this.hideSealed,
          limit: Search.PAGE,
        })
        .subscribe({
          next: (r) => {
            this.setResults(r);
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

  /**
   * Replaces the result list and starts a new page generation. Every path
   * that establishes a fresh list goes through here so none of them can
   * forget to invalidate a page request already in flight.
   */
  private setResults(rows: ItemSearchResult[]): void {
    this.generation++;
    this.results.set(rows);
    this.moreAvailable.set(rows.length === Search.PAGE);
  }

  /**
   * Appends the next page.
   *
   * Deliberately not routed through the debounced subject: switchMap would
   * cancel it against the next keystroke, and its whole job is to add to the
   * current list rather than replace it. The offset is the list's own length
   * rather than a counter, so the two cannot drift apart.
   *
   * Paging is only sound because item_search() orders by
   * (rank, length(name), name, product_id), which is deterministic down to
   * the tiebreaker. Verified against the live API with three consecutive
   * pages of "Charizard": zero overlap, identical on a repeat call. An
   * ORDER BY that ties would silently duplicate and skip rows here.
   */
  protected loadMore(): void {
    const trimmed = this.query.trim();
    if (!trimmed || this.loadingMore()) return;

    const issuedUnder = this.generation;
    this.loadingMore.set(true);
    this.api
      .search(trimmed, {
        game: this.game,
        includeSealed: !this.hideSealed,
        limit: Search.PAGE,
        offset: this.results().length,
      })
      .subscribe({
        next: (rows) => {
          this.loadingMore.set(false);
          if (issuedUnder !== this.generation) return; // a newer search won
          this.results.update((cur) => [...cur, ...rows]);
          this.moreAvailable.set(rows.length === Search.PAGE);
        },
        error: () => {
          this.loadingMore.set(false);
          this.error.set('Could not load more results.');
        },
      });
  }

  protected select(item: ItemSearchResult): void {
    this.selected.set(item);
    this.variants.set([]);
    this.history.set([]);
    this.activeVariant.set(null);
    this.provenance.set(null);
    this.loadingDetail.set(true);

    // Fire-and-forget alongside variants: a missing verdict must never hold
    // up the prices, which are what the screen is actually for. A 404 is the
    // ordinary "we have no image" case and stays silent.
    this.api.imageProvenance(item.productId).subscribe({
      next: (p) => {
        // The user may have clicked another card while this was in flight.
        if (this.selected()?.productId === p.productId) this.provenance.set(p);
      },
      error: () => this.provenance.set(null),
    });

    this.api.variants(item.productId).subscribe({
      next: (v) => {
        this.variants.set(v);
        this.loadingDetail.set(false);
        // Open on the most valuable variant. For a card with a $5 Normal
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

  /**
   * Spells out what the band means. Built here rather than in the template
   * because it is three conditional clauses, and a title attribute assembled
   * inline is the kind of thing that quietly loses a space.
   */
  protected provenanceTooltip(): string {
    const p = this.provenance();
    if (!p || p.provenance !== 'base_art') return '';
    const printing = p.strippedQualifier ? ` (${p.strippedQualifier})` : '';
    const shown = p.ownerName ? `"${p.ownerName}"` : 'another printing';
    return (
      `Our image source has no photo of this printing${printing}, ` +
      `so ${shown} is shown instead. The prices are this card's; ` +
      `the picture may not be.`
    );
  }

  protected showVariant(productId: number, subType: string): void {
    this.activeVariant.set(subType);
    this.history.set([]);
    this.api.history(productId, subType, this.windowFrom(subType)).subscribe({
      next: (h) => this.history.set(h),
      error: () => this.error.set(`No history for ${subType}.`),
    });
  }

  /** Re-fetches the open variant over the newly chosen range. */
  protected setChartWindow(w: HistoryWindow): void {
    if (this.chartWindow() === w) return;
    this.chartWindow.set(w);
    const productId = this.selected()?.productId;
    const subType = this.activeVariant();
    if (productId != null && subType) this.showVariant(productId, subType);
  }

  /**
   * Start date for the current range, or undefined for "All".
   *
   * The range is anchored on this variant's own newest snapshot rather
   * than on today. See history-window.ts for why a clock is the wrong
   * anchor here.
   *
   * That anchor also makes an empty result impossible, which is worth
   * knowing before writing a branch for one. item_variants() reports
   * latestDate as MAX(snapshot_date) for this exact sub-type, so the range
   * always contains at least that day's row. The endpoint 404s on an empty
   * history, and from this screen that can only mean a real failure, so it
   * still surfaces as one.
   *
   * A variant with no latest date can't happen here either, since the
   * buttons are built from the same list, so that fallback is just the
   * unwindowed request rather than a guess.
   */
  private windowFrom(subType: string): string | undefined {
    const days = this.chartWindow().days;
    if (days === null) return undefined;
    const variant = this.variants().find((v) => v.subTypeName === subType);
    return variant ? windowStart(variant.latestDate, days) : undefined;
  }

  protected clearSelection(): void {
    this.selected.set(null);
    this.variants.set([]);
    this.history.set([]);
    this.activeVariant.set(null);
  }

  /** Percent change across the loaded series, or null if unusable. */
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
