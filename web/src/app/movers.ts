import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CardTrackerApi } from './api';
import { Game, GameSet, Mover, PricePoint } from './api.models';
import { defaultGame } from './game-default';
import { PriceChart } from './price-chart';

/**
 * Movers - "what moved this week".
 *
 * Defaults to the biggest decliners over 7 days within the LARGEST tracked
 * game, which is the question a seller actually opens the app with. Set
 * filter narrows it once you know where you're looking.
 *
 * It used to default to all games at once; that ranked Pokemon and One
 * Piece against each other in one list, which compares markets the owner
 * considers separately. "All games" is still selectable - see
 * defaultGame().
 *
 * Every default here is the SERVER's default (see item_movers()), not a
 * restatement: days, direction, min price and smoothing are all left unset
 * on the first load so there is exactly one place defining what the default
 * view means.
 */
@Component({
  selector: 'app-movers',
  imports: [FormsModule, CurrencyPipe, DecimalPipe, PriceChart],
  templateUrl: './movers.html',
  styleUrl: './movers.css',
})
export class Movers {
  private readonly api = inject(CardTrackerApi);

  protected readonly games = signal<Game[]>([]);
  protected readonly sets = signal<GameSet[]>([]);
  protected readonly rows = signal<Mover[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly selected = signal<Mover | null>(null);
  protected readonly history = signal<PricePoint[]>([]);

  protected game: number | null = null;
  protected set: number | null = null;
  protected days = 7;
  protected direction: 'up' | 'down' = 'down';
  protected minPrice = 1;
  protected includeSealed = false;

  constructor() {
    this.api.games().subscribe({
      next: (g) => {
        this.games.set(g);
        // Land on one game rather than a mixed board. onGameChange() both
        // loads the set filter for it and triggers the first search, so the
        // opening view is the same one you get by picking a game by hand.
        this.game = defaultGame(g);
        this.onGameChange();
      },
      error: () => this.error.set('Could not reach the API. Is ApiServer running on :7070?'),
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.clearSelection();
    this.api
      .movers({
        days: this.days,
        game: this.game,
        set: this.set,
        includeSealed: this.includeSealed,
        minPrice: this.minPrice,
        direction: this.direction,
        limit: 50,
      })
      .subscribe({
        next: (r) => {
          this.rows.set(r);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Could not load movers.');
        },
      });
  }

  /** Changing game invalidates the set filter - a set belongs to one game. */
  protected onGameChange(): void {
    this.set = null;
    this.sets.set([]);
    if (this.game != null) {
      this.api.sets(this.game).subscribe({
        next: (s) => this.sets.set(s),
        error: () => this.error.set('Could not load sets for that game.'),
      });
    }
    this.load();
  }

  /**
   * A movers row already names its exact variant, so unlike the search
   * screen there's no variant-picking step - go straight to the history.
   */
  protected select(row: Mover): void {
    this.selected.set(row);
    this.history.set([]);
    this.api.history(row.productId, row.subTypeName).subscribe({
      next: (h) => this.history.set(h),
      error: () => this.error.set('Could not load history for that item.'),
    });
  }

  protected clearSelection(): void {
    this.selected.set(null);
    this.history.set([]);
  }
}
