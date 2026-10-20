// Live scores for the TV's info bar (backlog item 4): ESPN's public scoreboards, cut down to what
// the bar shows and kept for a minute so a living room zapping between games makes one call.
// Nothing about the TV or what it watches is sent or kept.

/** The leagues a TV may ask for, and their ESPN scoreboard paths. */
export const SCORE_LEAGUES: Record<string, string> = {
  nhl: "hockey/nhl",
  nba: "basketball/nba",
  wnba: "basketball/wnba",
  nfl: "football/nfl",
  mlb: "baseball/mlb",
  mls: "soccer/usa.1",
  epl: "soccer/eng.1",
  ucl: "soccer/uefa.champions",
  laliga: "soccer/esp.1",
};

export interface ScoreTeam {
  /** The nickname ("Maple Leafs"), what the TV matches against programme titles. */
  name: string;
  short: string;
  full: string;
  abbr: string;
  score: string;
}

export interface ScoreGame {
  state: "pre" | "in" | "post";
  /** ESPN's own short line: "2nd - 12:34", "Final", "7:00 PM ET". */
  detail: string;
  home: ScoreTeam;
  away: ScoreTeam;
}

const CACHE_MS = 60_000;
/** On a failed call, a minute-old answer is better than none for a while. */
const STALE_MS = 10 * 60_000;
const MAX_GAMES = 40;

const str = (v: unknown): string => (typeof v === "string" ? v.slice(0, 80) : typeof v === "number" ? String(v) : "");

function team(c: Record<string, unknown> | undefined): ScoreTeam | null {
  const t = c?.team as Record<string, unknown> | undefined;
  if (!t) return null;
  const full = str(t.displayName);
  const short = str(t.shortDisplayName) || full;
  const name = str(t.name) || short;
  if (!name) return null;
  return { name, short, full: full || short, abbr: str(t.abbreviation), score: str(c?.score) };
}

/** Today's games from an ESPN scoreboard answer; anything malformed is skipped. */
export function parseScoreboard(raw: unknown): ScoreGame[] {
  const events = (raw as { events?: unknown })?.events;
  if (!Array.isArray(events)) return [];
  const out: ScoreGame[] = [];
  for (const e of events.slice(0, MAX_GAMES)) {
    const comp = (e?.competitions as Record<string, unknown>[] | undefined)?.[0];
    const sides = comp?.competitors as Record<string, unknown>[] | undefined;
    if (!Array.isArray(sides)) continue;
    const home = team(sides.find((s) => s?.homeAway === "home"));
    const away = team(sides.find((s) => s?.homeAway === "away"));
    const type = ((comp?.status ?? e?.status) as { type?: Record<string, unknown> } | undefined)?.type;
    const state = type?.state;
    if (!home || !away || (state !== "pre" && state !== "in" && state !== "post")) continue;
    out.push({ state, detail: str(type?.shortDetail) || str(type?.detail), home, away });
  }
  return out;
}

const cache = new Map<string, { at: number; games: ScoreGame[] }>();

/** For tests: forget every cached scoreboard. */
export function resetScores() {
  cache.clear();
}

/** [league]'s games, from the cache when under a minute old. */
export async function scoresFor(league: string, now = Date.now()): Promise<ScoreGame[]> {
  if (!Object.hasOwn(SCORE_LEAGUES, league)) return [];
  const path = SCORE_LEAGUES[league];
  const hit = cache.get(league);
  if (hit && now - hit.at < CACHE_MS) return hit.games;
  try {
    const res = await fetch(`https://site.api.espn.com/apis/site/v2/sports/${path}/scoreboard`, {
      headers: { accept: "application/json" },
      signal: AbortSignal.timeout(5_000),
    });
    if (!res.ok) throw new Error(`scoreboard ${res.status}`);
    const games = parseScoreboard(await res.json());
    cache.set(league, { at: now, games });
    return games;
  } catch {
    return hit && now - hit.at < STALE_MS ? hit.games : [];
  }
}
