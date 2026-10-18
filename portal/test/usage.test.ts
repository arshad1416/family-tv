import { describe, expect, it } from "vitest";
import { sealJson, sha256Hex } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { USAGE_KEEP_MS, usageEvent } from "../src/usage";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));

/** A TV on a profile with usage stats on (lounge) and one on a profile without (cottage). */
async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin" } as unknown as Env;
  for (const [id, data] of [["home", { sources: [], usageStats: true }], ["away", { sources: [] }]] as const) {
    await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES (?, ?, ?, 0, 0)").bind(id, id, await sealJson(KEY, data)).run();
  }
  for (const [id, profile] of [["lounge", "home"], ["cottage", "away"], ["new", null]]) {
    await env.DB.prepare("INSERT INTO devices (id, token_hash, profile_id, created_at) VALUES (?, ?, ?, 0)").bind(id, await sha256Hex(`${id}-token`), profile).run();
  }
  return env;
}

const push = (env: Env, device: string, payload: unknown) =>
  worker.fetch(
    new Request("https://portal.test/api/device/usage", {
      method: "POST",
      headers: { authorization: `Bearer ${device}-token`, "content-type": "application/json" },
      body: JSON.stringify(payload),
    }),
    env,
  );

const rows = async (env: Env) =>
  (await env.DB.prepare("SELECT device_id, profile_id, app, app_version, screen, action, value FROM usage_events ORDER BY id").all()).results;

describe("anonymous usage stats", () => {
  it("keeps events from profiles with it on, and tells other TVs it's off", async () => {
    const env = await setup();
    const now = Date.now();
    const events = [{ at: now, screen: "search", action: "search_submit", value: 4 }, { at: now, screen: "settings", action: "setting_change", value: "stream_quality_live" }];
    const on = await push(env, "lounge", { app: "kotlin", appVersion: "0.2.45", events });
    expect(await on.json()).toEqual({ ok: true, on: true, saved: 2 });
    expect(await push(env, "cottage", { app: "rust", events }).then((r) => r.json())).toEqual({ ok: true, on: false, saved: 0 });
    expect((await push(env, "new", { app: "kotlin", events })).status).toBe(403);
    expect(await rows(env)).toEqual([
      { device_id: "lounge", profile_id: "home", app: "kotlin", app_version: "0.2.45", screen: "search", action: "search_submit", value: "4" },
      { device_id: "lounge", profile_id: "home", app: "kotlin", app_version: "0.2.45", screen: "settings", action: "setting_change", value: "stream_quality_live" },
    ]);
  });

  it("never stores free text: unknown names drop the event, an unknown value drops the value", async () => {
    const env = await setup();
    const now = Date.now();
    await push(env, "lounge", {
      app: "kotlin",
      events: [
        { at: now, screen: "CNN HD", action: "open" },
        { at: now, screen: "player", action: "watched Dune" },
        { at: now, screen: "search", action: "search_submit", value: "the bear" },
        { at: now, screen: "player", action: "play_fail", value: "decoder" },
      ],
    });
    expect((await rows(env)).map((r) => [r.screen, r.action, r.value])).toEqual([["search", "search_submit", null], ["player", "play_fail", "decoder"]]);
  });

  it("refuses big batches and unknown apps", async () => {
    const env = await setup();
    const one = { at: Date.now(), screen: "home", action: "open" };
    expect((await push(env, "lounge", { app: "kotlin", events: Array(501).fill(one) })).status).toBe(400);
    expect((await push(env, "lounge", { app: "web", events: [one] })).status).toBe(400);
  });

  it("deletes rows past 30 days as new ones arrive", async () => {
    const env = await setup();
    const now = Date.now();
    await env.DB.prepare("INSERT INTO usage_events (device_id, profile_id, app, at, screen, action) VALUES ('lounge', 'home', 'kotlin', ?, 'home', 'open')").bind(now - USAGE_KEEP_MS - 1000).run();
    await push(env, "lounge", { app: "kotlin", events: [{ at: now, screen: "home", action: "leave", value: 12 }] });
    expect((await rows(env)).map((r) => r.action)).toEqual(["leave"]);
  });

  it("caps a TV clock running ahead and drops events too old to keep", () => {
    const now = 1_000_000_000_000;
    expect(usageEvent({ at: now + 60_000, screen: "home", action: "open" }, now)?.at).toBe(now);
    expect(usageEvent({ at: now - USAGE_KEEP_MS - 1, screen: "home", action: "open" }, now)).toBeNull();
    expect(usageEvent({ at: "soon", screen: "home", action: "open" }, now)).toBeNull();
  });
});
