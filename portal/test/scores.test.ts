import { afterEach, describe, expect, it, vi } from "vitest";
import worker, { type Env } from "../src/index";
import { parseScoreboard, resetScores } from "../src/scores";

const handle = (req: Request, e: Env) => worker.fetch(req, e);

/** Just enough D1 for requireDevice: one device row. */
function fakeDb(device: Record<string, unknown> | null) {
  const stmt = (sql: string) => ({
    bind: () => stmt(sql),
    first: async () => (sql.includes("token_hash") ? device : null),
    run: async () => ({}),
    all: async () => ({ results: [] }),
  });
  return { prepare: stmt, batch: async () => [] } as unknown as D1Database;
}

const env = (device: Record<string, unknown> | null = { id: "d1", profile_id: "p1" }) =>
  ({ DB: fakeDb(device), CONFIG_KEY: "unused" }) as unknown as Env;

const get = (path: string) => new Request(`https://portal.test${path}`, { headers: { authorization: "Bearer device-token" } });

const side = (homeAway: string, name: string, short: string, full: string, abbr: string, score: string) =>
  ({ homeAway, score, team: { name, shortDisplayName: short, displayName: full, abbreviation: abbr, logo: "https://x/y.png" } });

const BOARD = {
  leagues: [{ name: "National Hockey League" }],
  events: [
    {
      status: { type: { state: "in", shortDetail: "2nd - 12:34" } },
      competitions: [{ competitors: [side("home", "Canadiens", "Canadiens", "Montreal Canadiens", "MTL", "1"), side("away", "Maple Leafs", "Maple Leafs", "Toronto Maple Leafs", "TOR", "2")] }],
    },
    { status: { type: { state: "pre", shortDetail: "7:00 PM ET" } }, competitions: [{ competitors: [side("home", "Oilers", "Oilers", "Edmonton Oilers", "EDM", "0")] }] },
    { status: { type: { state: "postponed" } }, competitions: [{ competitors: [side("home", "A", "A", "A", "A", "0"), side("away", "B", "B", "B", "B", "0")] }] },
  ],
};

function mockEspn(status = 200, body: unknown = BOARD) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => {
    calls.push(String(url));
    return new Response(JSON.stringify(body), { status });
  });
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  resetScores();
});

describe("live scores", () => {
  it("keeps only whole games, with just the names, scores and ESPN's short line", () => {
    expect(parseScoreboard(BOARD)).toEqual([
      {
        state: "in",
        detail: "2nd - 12:34",
        home: { name: "Canadiens", short: "Canadiens", full: "Montreal Canadiens", abbr: "MTL", score: "1" },
        away: { name: "Maple Leafs", short: "Maple Leafs", full: "Toronto Maple Leafs", abbr: "TOR", score: "2" },
      },
    ]);
    expect(parseScoreboard({})).toEqual([]);
    expect(parseScoreboard(null)).toEqual([]);
  });

  it("asks ESPN once a minute per league", async () => {
    const calls = mockEspn();
    const first = await handle(get("/api/device/scores?league=nhl"), env());
    expect(first.status).toBe(200);
    expect(((await first.json()) as { games: unknown[] }).games).toHaveLength(1);
    await handle(get("/api/device/scores?league=nhl"), env());
    expect(calls).toEqual(["https://site.api.espn.com/apis/site/v2/sports/hockey/nhl/scoreboard"]);
  });

  it("answers with no games when ESPN fails", async () => {
    mockEspn(503, {});
    const res = await handle(get("/api/device/scores?league=nba"), env());
    expect(await res.json()).toEqual({ league: "nba", games: [] });
  });

  it("refuses unknown leagues and unpaired TVs", async () => {
    mockEspn();
    expect((await handle(get("/api/device/scores?league=constructor"), env())).status).toBe(400);
    expect((await handle(get("/api/device/scores"), env())).status).toBe(400);
    expect((await handle(get("/api/device/scores?league=nhl"), env({ id: "d2", profile_id: null }))).status).toBe(403);
  });
});
