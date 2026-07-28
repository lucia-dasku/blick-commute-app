/**
 * Ranking logic for `GET /api/v1/stops/search`. Pure and side-effect free so it can be
 * unit tested directly against fixture data (see tests/search.test.ts) without a network
 * dependency on SL Transport's `/v1/sites` endpoint.
 */

export interface SearchableSite {
  siteId: number;
  name: string;
  note: string | null;
}

export type SearchTier = "exact" | "prefix" | "tokenPrefix" | "substring";

export interface RankedResult<T> {
  site: T;
  tier: SearchTier;
}

export const MIN_QUERY_LENGTH = 1;
export const MAX_QUERY_LENGTH = 64;
export const MAX_RESULTS = 20;

export class InvalidSearchQueryError extends Error {}

export function normalizeSearchQuery(rawQuery: string): string {
  const trimmed = rawQuery.trim();
  if (trimmed.length < MIN_QUERY_LENGTH) {
    throw new InvalidSearchQueryError(`Query must be at least ${MIN_QUERY_LENGTH} character(s) long`);
  }
  if (trimmed.length > MAX_QUERY_LENGTH) {
    throw new InvalidSearchQueryError(`Query must be at most ${MAX_QUERY_LENGTH} characters long`);
  }
  return trimmed;
}

/**
 * Case-insensitive, diacritic-tolerant fold, e.g. "Fruängen" and "fruangen" both fold to
 * "fruangen". This matters because Swedish names routinely contain å/ä/ö and travelers
 * frequently search without them on non-Swedish keyboards.
 */
export function foldForSearch(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

function tokenize(folded: string): string[] {
  return folded.split(/\s+/).filter(Boolean);
}

function classify(folded: string, foldedQuery: string): SearchTier | undefined {
  if (folded === foldedQuery) return "exact";
  if (folded.startsWith(foldedQuery)) return "prefix";
  if (tokenize(folded).some((token) => token.startsWith(foldedQuery))) return "tokenPrefix";
  if (folded.includes(foldedQuery)) return "substring";
  return undefined;
}

const TIER_RANK: Record<SearchTier, number> = {
  exact: 0,
  prefix: 1,
  tokenPrefix: 2,
  substring: 3,
};

/**
 * Ranks `sites` against `query`. Matches against `name` first; falls back to `note`
 * (e.g. a disambiguating locality name) using one tier worse than a `name` match at the
 * same level, so a `name` prefix match always outranks a `note` prefix match.
 * Ties are broken deterministically by folded name, then by siteId, so results are
 * stable across calls for identical input.
 */
export function rankSites<T extends SearchableSite>(sites: readonly T[], rawQuery: string): T[] {
  const query = normalizeSearchQuery(rawQuery);
  const foldedQuery = foldForSearch(query);

  const ranked: Array<{ site: T; rank: number; foldedName: string }> = [];

  for (const site of sites) {
    const foldedName = foldForSearch(site.name);
    const nameTier = classify(foldedName, foldedQuery);
    let bestTier = nameTier;
    let sourcePenalty = 0;

    if (!bestTier && site.note) {
      const foldedNote = foldForSearch(site.note);
      const noteTier = classify(foldedNote, foldedQuery);
      if (noteTier) {
        bestTier = noteTier;
        sourcePenalty = 0.5; // note matches rank just behind an equivalent name match
      }
    }

    if (!bestTier) continue;

    ranked.push({ site, rank: TIER_RANK[bestTier] + sourcePenalty, foldedName });
  }

  ranked.sort((a, b) => {
    if (a.rank !== b.rank) return a.rank - b.rank;
    if (a.foldedName !== b.foldedName) return a.foldedName < b.foldedName ? -1 : 1;
    return a.site.siteId - b.site.siteId;
  });

  return ranked.slice(0, MAX_RESULTS).map((r) => r.site);
}
