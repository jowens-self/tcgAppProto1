/**
 * Mirrors the Java records returned by the read API (see
 * com.cardtracker.model.* and README "Read API"). Field names match the
 * JSON exactly - if a record changes on the server, change it here too.
 */

/** A tracked game, from GET /api/games. */
export interface Game {
  categoryId: number;
  name: string;
  productCount: number;
}

/**
 * One search hit - a product, NOT a (product, variant) pair. A card that
 * exists as Normal / Holofoil / Reverse Holofoil is one result with
 * variantCount = 3, because those trade at genuinely different prices and
 * there is no single honest price to show. Call variants() for real prices.
 *
 * cardNumber and rarity are identity, not decoration: a set routinely
 * reprints one card at several rarities across a huge price range (SV03
 * Obsidian Flames has four "Charizard ex" from $5.65 to $108.58). Null for
 * sealed product, which has neither.
 *
 * NOTE: there is deliberately no `imageUrl` field here, even though the API
 * returns one. Those URLs point at tcgplayer-cdn.tcgplayer.com, and
 * rendering them makes the browser fetch from TCGplayer on every row. This
 * project sends NO traffic to tcgplayer.com, ever - see CLAUDE.md "Hard
 * constraints". The field is left off this interface so it cannot be bound
 * to an <img> by accident. Do not add it back.
 */
export interface ItemSearchResult {
  productId: number;
  name: string;
  cardNumber: string | null;
  rarity: string | null;
  /**
   * Printing qualifier pulled out of the name by variant_label() -
   * "Cracked Ice Holo", "Poke Ball Pattern", "2016 World Championship" -
   * or null for an ordinary printing.
   *
   * Two jobs. It is identity, like cardNumber: a variant is a separate
   * product at a separate price. It also marks cards whose thumbnail may be
   * the BASE printing rather than this exact variant, because the image
   * matcher resolves some variants to their original set (see
   * DEVELOPMENT_HISTORY.md Part 21). Wherever a variant's image is shown,
   * this label must be shown with it.
   */
  variantLabel: string | null;
  setName: string;
  game: string;
  isSealed: boolean;
  variantCount: number;
  latestDate: string | null;
  /**
   * Link to the product page. Safe: an href makes no request - nothing is
   * fetched unless the user deliberately clicks it, which is them browsing
   * TCGplayer themselves, exactly as they would anyway.
   */
  url: string | null;
}

/** One variant of a product, from GET /api/items/{id}/variants. */
export interface ItemVariant {
  subTypeName: string;
  firstDate: string;
  latestDate: string;
  /** Raw market price - null means none was recorded that day. */
  latestMarket: number | null;
  allTimeHigh: number | null;
}

/**
 * One day of price history. No highPrice field on purpose: sellers
 * "price park" (list absurdly high so nothing sells), so the API never
 * returns it. trendablePrice is the market->mid->low fallback already
 * applied server-side - chart that, don't re-derive it.
 */
export interface PricePoint {
  snapshotDate: string;
  marketPrice: number | null;
  lowPrice: number | null;
  midPrice: number | null;
  directLowPrice: number | null;
  trendablePrice: number | null;
}

/** A set within a game, for the movers set filter. */
export interface GameSet {
  groupId: number;
  name: string;
  publishedOn: string | null;
  productCount: number;
}

/**
 * One row of the movers board.
 *
 * Per (product, VARIANT), unlike ItemSearchResult which is per product — a
 * mover is one specific market's move, and Holofoil / Reverse Holofoil of
 * the same card move independently.
 *
 * thenPrice/nowPrice are averages over the smoothing window at each end, not
 * single-day prices. Thinly-traded cards lurch on single sales, so a raw
 * two-point comparison puts artifacts at the top of the board.
 */
export interface Mover {
  productId: number;
  name: string;
  cardNumber: string | null;
  rarity: string | null;
  setName: string;
  game: string;
  subTypeName: string;
  isSealed: boolean;
  thenPrice: number;
  nowPrice: number;
  pctChange: number;
  url: string | null;
}
