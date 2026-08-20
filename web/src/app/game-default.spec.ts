import { describe, expect, it } from 'vitest';
import { defaultGame } from './game-default';
import { Game } from './api.models';

const game = (categoryId: number, name: string, productCount: number): Game =>
  ({ categoryId, name, productCount }) as Game;

/**
 * Which game Movers and Sets open on.
 *
 * "All games" stopped being the default on 2026-08-19 because it produced a
 * board mixing two markets the owner thinks about separately - roughly 41
 * Pokemon to 9 One Piece in a typical top 50.
 */
describe('defaultGame', () => {
  it('opens on the largest tracked catalog', () => {
    expect(
      defaultGame([
        game(68, 'One Piece Card Game', 11_000),
        game(3, 'Pokemon', 38_000),
      ]),
    ).toBe(3);
  });

  /**
   * The important one. Which games are tracked is DATA, not code -
   * tracked_categories decides it and adding a game is an INSERT. Pinning 3
   * here would put Pokemon in the source of a screen meant not to know which
   * games exist, so the choice must follow the counts wherever they lead.
   */
  it('is chosen by size, never by a hardcoded category id', () => {
    expect(
      defaultGame([
        game(3, 'Pokemon', 900),
        game(68, 'One Piece Card Game', 40_000),
      ]),
    ).toBe(68);

    // A third game arriving by INSERT must be able to win without a code change.
    expect(
      defaultGame([
        game(3, 'Pokemon', 38_000),
        game(68, 'One Piece Card Game', 11_000),
        game(1, 'Magic', 500_000),
      ]),
    ).toBe(1);
  });

  /** Null reads as "all games" - the only sensible choice among nothing. */
  it('returns null for an empty list', () => {
    expect(defaultGame([])).toBeNull();
  });

  it('handles a single tracked game', () => {
    expect(defaultGame([game(3, 'Pokemon', 38_000)])).toBe(3);
  });
});
