import { describe, expect, it } from "vitest";
import { sealJson, sha256Hex } from "../src/crypto";
import worker, { progressRow, type Env } from "../src/index";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));

/** Two TVs in one profile (lounge, bedroom) and one in another (away). */
async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin" } as unknown as Env;
  for (const id of ["home", "cottage"]) {
    await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES (?, ?, ?, 0, 0)").bind(id, id, await sealJson(KEY, { sources: [] })).run();
  }
  for (const [id, profile] of [["lounge", "home"], ["bedroom", "home"], ["away", "cottage"]]) {
    await env.DB.prepare("INSERT INTO devices (id, token_hash, profile_id, created_at) VALUES (?, ?, ?, 0)").bind(id, await sha256Hex(`${id}-token`), profile).run();
  }
  return env;
}

const call = (env: Env, device: string, method: string, path: string, payload?: unknown) =>
  worker.fetch(
    new Request(`https://portal.test${path}`, {
      method,
      headers: { authorization: `Bearer ${device}-token`, "content-type": "application/json" },
      body: payload === undefined ? undefined : JSON.stringify(payload),
    }),
    env,
  );

interface Page { profile: string; rows: { key: string; watched: boolean; positionMs: number; updatedAt: number }[]; seq: number; more: boolean }
const pull = async (env: Env, device: string, since = 0) => (await call(env, device, "GET", `/api/device/progress?since=${since}`)).json<Page>();
const push = (env: Env, device: string, rows: unknown[]) => call(env, device, "POST", "/api/device/progress", { rows });

const episode = (n: number, over: Record<string, unknown> = {}) => ({
  key: `e/src1/77/1/${n}`, kind: "episode", sourceId: "src1", itemId: "77", season: 1, episode: n,
  positionMs: 0, durationMs: 2_600_000, percent: 100, watched: true, updatedAt: 1_000, ...over,
});

describe("watched marks and resume points shared by a profile", () => {
  it("gives a TV what another TV in the profile marked, and nothing to other profiles", async () => {
    const env = await setup();
    expect((await push(env, "lounge", [episode(1), episode(2)])).status).toBe(200);

    const page = await pull(env, "bedroom");
    expect(page.profile).toBe("home");
    expect(page.rows.map((r) => r.key)).toEqual(["e/src1/77/1/1", "e/src1/77/1/2"]);
    expect(page.rows[0].watched).toBe(true);
    expect(page.more).toBe(false);
    expect((await pull(env, "bedroom", page.seq)).rows).toEqual([]);
    expect((await pull(env, "away")).rows).toEqual([]);
  });

  it("keeps the later change, and hands only changed rows to the next pull", async () => {
    const env = await setup();
    await push(env, "lounge", [episode(1, { watched: false, percent: 40, positionMs: 900_000, updatedAt: 5_000 })]);
    const first = await pull(env, "bedroom");

    // An older mark from a TV that was offline doesn't undo the newer resume spot.
    await push(env, "bedroom", [episode(1, { updatedAt: 4_000 })]);
    expect((await pull(env, "lounge", first.seq)).rows).toEqual([]);

    await push(env, "bedroom", [episode(1, { updatedAt: 6_000 })]);
    const next = await pull(env, "lounge", first.seq);
    expect(next.rows).toHaveLength(1);
    expect(next.rows[0]).toMatchObject({ watched: true, updatedAt: 6_000 });
    expect(next.seq).toBeGreaterThan(first.seq);
  });

  it("tells heartbeats when there is something new to pull", async () => {
    const env = await setup();
    const seq = async () => (await (await call(env, "bedroom", "POST", "/api/device/heartbeat", {})).json<{ progressSeq: number }>()).progressSeq;
    expect(await seq()).toBe(0);
    await push(env, "lounge", [episode(1)]);
    expect(await seq()).toBe(1);
  });

  it("pages large pulls", async () => {
    const env = await setup();
    for (let i = 0; i < 3; i++) await push(env, "lounge", Array.from({ length: 400 }, (_, n) => episode(i * 400 + n)));
    const a = await pull(env, "bedroom");
    expect(a.rows).toHaveLength(1000);
    expect(a.more).toBe(true);
    const b = await pull(env, "bedroom", a.seq);
    expect(b.rows).toHaveLength(200);
    expect(b.more).toBe(false);
  });

  it("refuses unpaired TVs and oversized pushes, and skips rows that aren't progress", async () => {
    const env = await setup();
    await env.DB.prepare("INSERT INTO devices (id, token_hash, created_at) VALUES ('new', ?, 0)").bind(await sha256Hex("new-token")).run();
    expect((await pull(env, "new")).rows).toBeUndefined();
    expect((await push(env, "lounge", Array.from({ length: 501 }, (_, n) => episode(n)))).status).toBe(400);
    const res = await push(env, "lounge", [episode(1), { key: "x/evil" }, episode(2, { kind: "channel" })]);
    expect(await res.json()).toMatchObject({ saved: 1 });
  });

  it("caps a TV clock that runs far ahead", () => {
    const now = 1_000_000;
    expect(progressRow(episode(1, { updatedAt: now + 86_400_000 }), now)!.updatedAt).toBe(now + 5 * 60 * 1000);
    expect(progressRow(episode(1, { percent: 250 }), now)!.percent).toBe(100);
  });
});
