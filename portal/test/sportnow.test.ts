import { afterEach, describe, expect, it, vi } from "vitest";
import { sealJson, sha256Hex } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { SPORT_LABELS, UNSURE, classifyProgrammes, parseChoice, sportKey } from "../src/sportnow";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));
const LABELS = Object.keys(SPORT_LABELS);

/** Probabilities over every label with [top] at [p] and the rest shared. */
function probs(top: string, p: number) {
  const rest = (1 - p) / (LABELS.length - 1);
  return Object.fromEntries(LABELS.map((l) => [l, l === top ? p : rest]));
}

/** A stand-in for OpenRouter's Jev route: answers each question with [pick](title). */
function mockJev(pick: (title: string) => { choice: string; p: number } | null, model = "typesafe/jev-1.13-20260918") {
  const calls: { questions: number; titles: string[] }[] = [];
  const fn = vi.fn(async (_url: string, init: RequestInit) => {
    const req = JSON.parse(String(init.body));
    const items = req.state.items as Record<string, { title: string }>;
    calls.push({ questions: Object.keys(req.questions).length, titles: Object.values(items).map((i) => i.title) });
    const answers: Record<string, unknown> = {};
    for (const [id, it] of Object.entries(items)) {
      const a = pick(it.title);
      if (a) answers[id] = { choice: a.choice, probabilities: probs(a.choice, a.p) };
    }
    return new Response(JSON.stringify({ model, answers }), { status: 200 });
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

async function setup(key: string | undefined = "or-key") {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin", OPENROUTER_API_KEY: key } as unknown as Env;
  await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES ('aa', 'aa', ?, 0, 0)").bind(await sealJson(KEY, { sources: [] })).run();
  await env.DB.prepare("INSERT INTO devices (id, token_hash, profile_id, created_at) VALUES ('abc1', ?, 'aa', 0)").bind(await sha256Hex("tv-token")).run();
  return env;
}

const rows = async (env: Env) => (await env.DB.prepare("SELECT key, label, p, source FROM sport_labels ORDER BY key").all<Record<string, unknown>>()).results;

afterEach(() => vi.unstubAllGlobals());

describe("sport labels", () => {
  it("keys on squashed title and genres", () => {
    expect(sportKey("  Leafs   at Canadiens ", "Sports, Ice Hockey")).toBe("leafs at canadiens|sports, ice hockey");
  });

  it("accepts only well formed answers", () => {
    expect(parseChoice({ choice: "Hockey", probabilities: probs("Hockey", 0.9) })).toEqual({ label: "Hockey", p: 0.9 });
    expect(parseChoice({ choice: "Curling", probabilities: probs("Hockey", 0.9) })).toBeNull();
    expect(parseChoice({ choice: "Soccer", probabilities: probs("Hockey", 0.9) })).toBeNull(); // not the argmax
    expect(parseChoice({ choice: "Hockey", probabilities: { Hockey: 1 } })).toBeNull();
    expect(parseChoice(undefined)).toBeNull();
  });

  it("stores confident answers and keeps unsure ones as Unsure", async () => {
    const env = await setup();
    const jev = mockJev((t) => (t.startsWith("Leafs") ? { choice: "Hockey", p: 0.93 } : { choice: "Soccer", p: 0.4 }));
    const out = await classifyProgrammes(env, [{ title: "Leafs at Canadiens" }, { title: "Mystery Cup" }], jev.fn, 1000);
    expect(out.get(sportKey("Leafs at Canadiens"))).toEqual({ label: "Hockey", p: 0.93 });
    expect(out.get(sportKey("Mystery Cup"))?.label).toBe(UNSURE);
    expect((await rows(env)).map((r) => r.label)).toEqual(["Hockey", UNSURE]);
  });

  it("answers from the cache without asking Jev again", async () => {
    const env = await setup();
    const first = mockJev(() => ({ choice: "Basketball", p: 0.9 }));
    await classifyProgrammes(env, [{ title: "Raptors at Knicks" }], first.fn, 1000);
    const again = mockJev(() => ({ choice: "Golf", p: 0.9 }));
    const out = await classifyProgrammes(env, [{ title: "raptors  at knicks" }], again.fn, 2000);
    expect(again.calls).toHaveLength(0);
    expect(out.get(sportKey("Raptors at Knicks"))?.label).toBe("Basketball");
  });

  it("stores nothing when Jev fails, answers badly or is another model", async () => {
    for (const fn of [
      (async () => { throw new Error("down"); }) as unknown as typeof fetch,
      (async () => new Response("nope", { status: 502 })) as unknown as typeof fetch,
      mockJev(() => ({ choice: "Hockey", p: 0.9 }), "someone/else").fn,
      mockJev(() => null).fn,
    ]) {
      const env = await setup();
      const out = await classifyProgrammes(env, [{ title: "Leafs at Canadiens" }], fn, 1000);
      expect(out.get(sportKey("Leafs at Canadiens"))).toBeNull();
      expect(await rows(env)).toEqual([]);
    }
  });

  it("never calls out without a key", async () => {
    const env = await setup("");
    const jev = mockJev(() => ({ choice: "Hockey", p: 0.9 }));
    const out = await classifyProgrammes(env, [{ title: "Leafs at Canadiens" }], jev.fn);
    expect(jev.calls).toHaveLength(0);
    expect(out.get(sportKey("Leafs at Canadiens"))).toBeNull();
  });

  it("asks in batches of 20", async () => {
    const env = await setup();
    const jev = mockJev(() => ({ choice: "Soccer", p: 0.9 }));
    await classifyProgrammes(env, Array.from({ length: 45 }, (_, i) => ({ title: `Match ${i}` })), jev.fn);
    expect(jev.calls.map((c) => c.questions)).toEqual([20, 20, 5]);
  });
});

describe("POST /api/device/sport-now", () => {
  const post = (b: unknown, token = "tv-token") =>
    new Request("https://portal.test/api/device/sport-now", { method: "POST", headers: { authorization: `Bearer ${token}` }, body: JSON.stringify(b) });

  it("labels each item in order for a paired TV", async () => {
    const env = await setup();
    vi.stubGlobal("fetch", mockJev((t) => (t === "UFC 320" ? { choice: "Combat", p: 0.97 } : null)).fn);
    const res = await worker.fetch(post({ items: [{ title: "UFC 320", genres: "Sports" }, { title: "Off air" }] }), env);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ enabled: true, labels: [{ title: "UFC 320", genres: "Sports", label: "Combat", p: 0.97 }, null] });
  });

  it("rejects unpaired TVs and bad bodies", async () => {
    const env = await setup();
    expect((await worker.fetch(post({ items: [] }, "wrong"), env)).status).toBe(401);
    expect((await worker.fetch(post({ items: "x" }), env)).status).toBe(400);
    expect((await worker.fetch(post({ items: [{ title: "" }] }), env)).status).toBe(400);
    expect((await worker.fetch(post({ items: [{ title: "x".repeat(201) }] }), env)).status).toBe(400);
    expect((await worker.fetch(post({ items: Array.from({ length: 201 }, () => ({ title: "a" })) }), env)).status).toBe(400);
  });
});
