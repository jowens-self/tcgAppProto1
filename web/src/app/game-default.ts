import { Game } from './api.models';

/**
 * Which game a screen should land on before the user picks one.
 *
 * "All games" is deliberately NOT the default any more (changed
 * 2026-08-19). It was, and it produced a board mixing Pokemon and One Piece
 * in one ranking (41 Pokemon to 9 One Piece in a typical top 50), which
 * compares two markets the owner thinks about separately. The option is
 * still there and still fast; it just isn't where you start.
 *
 * Chosen by catalog size rather than a hardcoded category id, because which
 * games are tracked is DATA, not code: `tracked_categories` decides that,
 * and adding a game is an INSERT (see CLAUDE.md "Multi-game support").
 * Pinning `3` here would put Pokemon in the source of a screen that is
 * meant not to know which games exist.
 *
 * Returns null for an empty list, which the callers treat as "all games" -
 * the only sensible reading when there is nothing to choose between.
 */
export function defaultGame(games: Game[]): number | null {
  if (games.length === 0) return null;
  return games.reduce((a, b) => (b.productCount > a.productCount ? b : a)).categoryId;
}
