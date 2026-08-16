import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Game, GameSet, ItemSearchResult, ItemVariant, Mover, PricePoint } from './api.models';

/**
 * The only thing in this app that knows a server exists.
 *
 * Every method is one endpoint from the read API. Nothing here talks to
 * Postgres or to tcgcsv - that boundary is deliberate (see CLAUDE.md
 * "Architecture intent"): the mirror has a restrictive CORS policy and
 * asks that data be ingested server-side, so the browser never touches it.
 */
@Injectable({ providedIn: 'root' })
export class CardTrackerApi {
  private readonly http = inject(HttpClient);

  /**
   * The API's own dev address. CORS on the server is scoped to
   * localhost:4200 (the Angular dev server), so this works in `ng serve`
   * and would need revisiting alongside that rule if either ever moves.
   */
  private readonly base = 'http://localhost:7070/api';

  games(): Observable<Game[]> {
    return this.http.get<Game[]>(`${this.base}/games`);
  }

  search(
    query: string,
    opts: { game?: number | null; includeSealed?: boolean; limit?: number; offset?: number } = {},
  ): Observable<ItemSearchResult[]> {
    let params = new HttpParams().set('q', query);
    if (opts.game != null) params = params.set('game', opts.game);
    // Only sent when hiding sealed - the server defaults to including it.
    if (opts.includeSealed === false) params = params.set('sealed', 'false');
    if (opts.limit != null) params = params.set('limit', opts.limit);
    if (opts.offset != null) params = params.set('offset', opts.offset);
    return this.http.get<ItemSearchResult[]>(`${this.base}/items/search`, { params });
  }

  sets(categoryId: number): Observable<GameSet[]> {
    return this.http.get<GameSet[]>(`${this.base}/games/${categoryId}/sets`);
  }

  /**
   * Biggest movers. Every option is omitted unless set, so the server's own
   * defaults (7 days, decliners, singles, $1 floor, 3-day smoothing) apply -
   * they live in item_movers() rather than being restated here.
   */
  movers(opts: {
    days?: number;
    game?: number | null;
    set?: number | null;
    includeSealed?: boolean;
    minPrice?: number;
    direction?: 'up' | 'down';
    limit?: number;
  } = {}): Observable<Mover[]> {
    let params = new HttpParams();
    if (opts.days != null) params = params.set('days', opts.days);
    if (opts.game != null) params = params.set('game', opts.game);
    if (opts.set != null) params = params.set('set', opts.set);
    if (opts.includeSealed) params = params.set('sealed', 'true');
    if (opts.minPrice != null) params = params.set('minPrice', opts.minPrice);
    if (opts.direction) params = params.set('direction', opts.direction);
    if (opts.limit != null) params = params.set('limit', opts.limit);
    return this.http.get<Mover[]>(`${this.base}/movers`, { params });
  }

  /**
   * URL of a card's thumbnail on OUR server. Never a third-party URL - the
   * browser must not fetch images from anyone else (see CLAUDE.md). Returns
   * 404 when we have no image, which the caller renders as a placeholder.
   */
  imageUrl(productId: number): string {
    return `${this.base}/items/${productId}/image`;
  }

  item(productId: number): Observable<ItemSearchResult> {
    return this.http.get<ItemSearchResult>(`${this.base}/items/${productId}`);
  }

  variants(productId: number): Observable<ItemVariant[]> {
    return this.http.get<ItemVariant[]>(`${this.base}/items/${productId}/variants`);
  }

  /**
   * subType is required by the server and has no default - picking one for
   * the caller would mean guessing which market they meant. Get valid
   * values from variants().
   */
  history(
    productId: number,
    subType: string,
    from?: string,
    to?: string,
  ): Observable<PricePoint[]> {
    let params = new HttpParams().set('subType', subType);
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<PricePoint[]>(`${this.base}/items/${productId}/history`, { params });
  }
}
