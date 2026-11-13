// What sport a TV guide programme is showing, for the TV's Sports tab. The TV settles what its own
// word lists can; the rest come here and go to Jev (TypeSafe's decision model, via OpenRouter).
// Answers are kept in D1 so every TV shares them and each programme is asked once. Only public
// guide text (title, description, genres) is sent; nothing about the TV or who is watching.

/** The labels Jev picks from. Sport names match the TV's `Sport` enum exactly. */
export const SPORT_LABELS: Record<string, string> = {
  Hockey: "Ice hockey: NHL, AHL, junior (OHL/WHL/QMJHL), KHL, international hockey.",
  AmericanFootball: "American or Canadian football: NFL, CFL, college football, UFL.",
  Soccer: "Association football (soccer): Premier League, MLS, Champions League, La Liga, World Cup.",
  Basketball: "Basketball: NBA, WNBA, college basketball, EuroLeague, FIBA.",
  Baseball: "Baseball: MLB, minor leagues, World Baseball Classic.",
  Cricket: "Cricket: Test, ODI, T20, IPL, The Ashes.",
  Tennis: "Tennis: ATP, WTA, Grand Slams.",
  Golf: "Golf: PGA Tour, LPGA, LIV, majors, Ryder Cup.",
  Motorsport: "Motor racing: Formula 1, NASCAR, IndyCar, MotoGP, rally, motocross.",
  Combat: "Combat sports: UFC, MMA, boxing, wrestling (WWE, AEW), kickboxing.",
  Rugby: "Rugby union or rugby league.",
  AussieRules: "Australian rules football (AFL).",
  Gaelic: "Gaelic games: hurling, Gaelic football, camogie.",
  Darts: "Darts, snooker or pool.",
  HorseRacing: "Horse racing or harness racing.",
  Cycling: "Cycling: road racing, track, Tour de France.",
  Volleyball: "Volleyball or beach volleyball.",
  Show: "A sports studio, talk, news, magazine, highlights, preview or analysis programme, not a game or event itself.",
  NotSport: "Not sport at all: a film, series, kids or news show, infomercial, or off air.",
};

/** Stored for answers Jev wasn't sure of, so they aren't asked again; the TV keeps its own guess. */
export const UNSURE = "Unsure";

const JEV_URL = "https://openrouter.ai/api/v1/systemone";
const JEV_MODEL = "typesafe/jev-1.13";
const MIN_P = 0.75;
const BATCH = 20;
const MAX_MISSES = 60;
const TIMEOUT_MS = 10_000;
const KEEP_MS = 30 * 24 * 3_600_000;

export interface ProgrammeIn {
  title: string;
  desc?: string;
  genres?: string;
}

export interface SportLabel {
  label: string;
  p: number | null;
}

const squash = (s: string | undefined) => (s ?? "").toLowerCase().replace(/\s+/g, " ").trim();

/** One cache row per programme title and genres. */
export function sportKey(title: string, genres?: string): string {
  return `${squash(title)}|${squash(genres)}`.slice(0, 300);
}

/** Jev's answer if it is well formed: a known label, probabilities over exactly the labels, choice the argmax. */
export function parseChoice(a: unknown): SportLabel | null {
  const { choice, probabilities: p } = (a ?? {}) as { choice?: unknown; probabilities?: Record<string, unknown> };
  if (typeof choice !== "string" || !Object.hasOwn(SPORT_LABELS, choice) || !p || typeof p !== "object") return null;
  const keys = Object.keys(p);
  const labels = Object.keys(SPORT_LABELS);
  if (keys.length !== labels.length || !labels.every((l) => Object.hasOwn(p, l))) return null;
  const vals = labels.map((l) => p[l]);
  if (!vals.every((v) => typeof v === "number" && Number.isFinite(v) && v >= 0 && v <= 1)) return null;
  const nums = vals as number[];
  if (Math.abs(nums.reduce((s, v) => s + v, 0) - 1) > 0.02 || (p[choice] as number) !== Math.max(...nums)) return null;
  return { label: choice, p: p[choice] as number };
}

/** One Jev call for up to [BATCH] programmes; null for each one it couldn't answer. */
async function askJev(key: string, items: ProgrammeIn[], fetchImpl: typeof fetch): Promise<(SportLabel | null)[]> {
  const state: Record<string, unknown> = {};
  const questions: Record<string, unknown> = {};
  items.forEach((it, i) => {
    state[`s${i}`] = { title: it.title, desc: (it.desc ?? "").slice(0, 200), genres: it.genres ?? "" };
    questions[`s${i}`] = {
      type: "choice",
      criteria: SPORT_LABELS,
      instructions: `Which sport is TV programme s${i} showing? Use the title, description and genres.`,
    };
  });
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    const res = await fetchImpl(JEV_URL, {
      method: "POST",
      headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
      body: JSON.stringify({ model: JEV_MODEL, state: { items: state }, questions }),
      signal: ctl.signal,
    });
    if (!res.ok) return items.map(() => null);
    const raw = (await res.json()) as { model?: unknown; answers?: Record<string, unknown> };
    if (!String(raw?.model ?? "").startsWith(JEV_MODEL)) return items.map(() => null);
    return items.map((_, i) => parseChoice(raw.answers?.[`s${i}`]));
  } catch {
    return items.map(() => null);
  } finally {
    clearTimeout(timer);
  }
}

/**
 * The label for each programme, keyed by [sportKey]: from the shared cache, else Jev (answers under
 * [MIN_P] are kept as [UNSURE]). Null when there's no key, Jev failed, or past [MAX_MISSES] new ones.
 */
export async function classifyProgrammes(
  env: { DB: D1Database; OPENROUTER_API_KEY?: string },
  items: ProgrammeIn[],
  fetchImpl: typeof fetch = fetch,
  now = Date.now(),
): Promise<Map<string, SportLabel | null>> {
  const byKey = new Map<string, ProgrammeIn>();
  for (const it of items) {
    const k = sportKey(it.title, it.genres);
    if (!byKey.has(k)) byKey.set(k, it);
  }
  const out = new Map<string, SportLabel | null>();
  const keys = [...byKey.keys()];
  for (let i = 0; i < keys.length; i += 50) {
    const chunk = keys.slice(i, i + 50);
    const { results } = await env.DB.prepare(
      `SELECT key, label, p FROM sport_labels WHERE created_at > ? AND key IN (${chunk.map(() => "?").join(",")})`,
    )
      .bind(now - KEEP_MS, ...chunk)
      .all<{ key: string; label: string; p: number | null }>();
    for (const r of results) out.set(r.key, { label: r.label, p: r.p });
  }
  const misses = keys.filter((k) => !out.has(k));
  for (const k of misses.slice(MAX_MISSES)) out.set(k, null);
  const asked = misses.slice(0, MAX_MISSES);
  if (!env.OPENROUTER_API_KEY) {
    for (const k of asked) out.set(k, null);
    return out;
  }
  for (let i = 0; i < asked.length; i += BATCH) {
    const batch = asked.slice(i, i + BATCH);
    const answers = await askJev(env.OPENROUTER_API_KEY, batch.map((k) => byKey.get(k)!), fetchImpl);
    const writes: D1PreparedStatement[] = [];
    batch.forEach((k, j) => {
      const a = answers[j];
      if (!a) return out.set(k, null);
      const kept = (a.p ?? 0) >= MIN_P ? a : { label: UNSURE, p: a.p };
      out.set(k, kept);
      const it = byKey.get(k)!;
      writes.push(
        env.DB.prepare(
          "INSERT OR REPLACE INTO sport_labels (key, title, genres, label, p, source, created_at) VALUES (?, ?, ?, ?, ?, 'jev', ?)",
        ).bind(k, it.title, it.genres ?? null, kept.label, kept.p, now),
      );
    });
    if (writes.length) await env.DB.batch(writes);
  }
  return out;
}
