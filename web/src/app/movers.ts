import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CardTrackerApi } from './api';
import { Game, GameSet, Mover, PricePoint,
  ChartDatum,
} from './api.models';
import { defaultGame } from './game-default';
import {
  LaunchPricingPrefs,
  PRE_RELEASE_TITLE,
  chartablePoints,
  earlyPricingTitle,
  earlyWeeks,
  launchNote,
} from './launch-pricing';
import { modernRule } from './modern-rule';
import { PriceChart } from './price-chart';

/**
 * Movers: "what moved this week".
 *
 * Defaults to the biggest decliners over 7 days within the LARGEST tracked
 * game, which is the question a seller actually opens the app with. Set
 * filter narrows it once you know where you're looking.
 *
 * It used to default to all games at once; that ranked Pokemon and One
 * Piece against each other in one list, which compares markets the owner
 * considers separately. "All games" is still selectable; see
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

  /**
   * Whether launch pricing counts. Shared with every screen, so the chart
   * below the board follows the same boxes as the board itself.
   */
  protected readonly launch = inject(LaunchPricingPrefs);
  protected readonly preReleaseTitle = PRE_RELEASE_TITLE;

  /** The price view of the history, less any launch pricing that's unticked. */
  private readonly chartView = computed(() =>
    chartablePoints<ChartDatum>(
      this.history().map((p) => ({ date: p.snapshotDate, value: p.trendablePrice, phase: p.phase })),
      this.launch.includePreRelease(),
      this.launch.includeEarly(),
    ),
  );
  protected readonly chartSeries = computed(() => this.chartView().points);
  protected readonly chartOnlyLaunch = computed(() => this.chartView().onlyLaunch);

  protected game: number | null = null;
  protected set: number | null = null;
  protected days = 7;
  protected direction: 'up' | 'down' = 'down';
  protected minPrice = 1;
  protected includeSealed = false;

  /**
   * A signal, unlike the fields above, because the set dropdown follows it
   * too. Filtered server-side for the board (it has to happen before the
   * top-50 cut) and client-side for the dropdown, both from the server's
   * answer, never a date comparison here.
   */
  protected readonly modernOnly = signal(false);

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
        modernOnly: this.modernOnly(),
        includePreRelease: this.launch.includePreRelease(),
        includeEarly: this.launch.includeEarly(),
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

  /** Changing game invalidates the set filter, since a set belongs to one game. */
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

  /** The set dropdown's options: only modern sets while that filter is on. */
  protected setOptions(): GameSet[] {
    return this.modernOnly() ? this.sets().filter((s) => s.modern) : this.sets();
  }

  /** What "Modern only" means for the game on screen. */
  protected modernNote(): string {
    return modernRule(this.games(), this.game);
  }

  protected earlyTitle(): string {
    return earlyPricingTitle(earlyWeeks(this.games(), this.game));
  }

  /** What the launch-pricing boxes are leaving off the board, or ''. */
  protected launchNote(): string {
    return launchNote(
      this.launch.includePreRelease(),
      this.launch.includeEarly(),
      earlyWeeks(this.games(), this.game),
      'the board',
    );
  }

  /**
   * Turning modern on drops a picked set that isn't modern. Keeping it would
   * leave an empty board for a reason nothing on screen explains.
   */
  protected onModernChange(on: boolean): void {
    this.modernOnly.set(on);
    if (on && this.set !== null && !this.setOptions().some((s) => s.groupId === this.set)) {
      this.set = null;
    }
    this.load();
  }

  /**
   * Thumbnails, served off our own disk and never from a TCGplayer host.
   *
   * Movers was built the same day local images landed and simply never got
   * them; there was no decision behind their absence. Cheap since Part 38,
   * when serving an image stopped opening a database connection.
   *
   * Misses are ordinary rather than exceptional - roughly 40% of Pokemon
   * singles have no counterpart upstream, and sealed product has none at all
   * - so a failure is recorded and the <img> dropped, leaving a sized box.
   * Letting the browser handle it would paint a broken-image glyph down a
   * column of prices.
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

  /**
   * A movers row already names its exact variant, so unlike the search
   * screen there's no variant-picking step, so go straight to the history.
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
