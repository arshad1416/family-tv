import { describe, expect, it } from "vitest";
import { sealJson, sha256Hex } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { validateProfile } from "../src/model";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(3)));

async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin" } as unknown as Env;
  await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES ('ab12', 'Home', ?, 0, 0)").bind(await sealJson(KEY, { sources: [] })).run();
  await env.DB.prepare("INSERT INTO devices (id, token_hash, profile_id, created_at) VALUES ('d1', ?, 'ab12', 0)").bind(await sha256Hex("tv-token")).run();
  return env;
}

const call = (env: Env, path: string, token: string, init: RequestInit = {}) =>
  worker.fetch(new Request("https://portal.test" + path, { ...init, headers: { authorization: "Bearer " + token, "content-type": "application/json" } }), env).then(async (r) => ({
    status: r.status,
    data: (await r.json()) as any,
  }));

describe("category arranging", () => {
  it("stores what a TV reports and shows it for its profile", async () => {
    const env = await setup();
    const sent = { live: ["News", " Sports ", "news", 7], movie: [], series: ["Kids"] };
    expect((await call(env, "/api/device/categories", "tv-token", { method: "POST", body: JSON.stringify(sent) })).status).toBe(200);
    const { data } = await call(env, "/api/admin/profiles/ab12/categories", "admin");
    expect(data.reported.live.names).toEqual(["News", "Sports"]);
    expect(data.reported.series.names).toEqual(["Kids"]);
    expect(data.reported.movie).toBeUndefined();
  });

  it("validates a saved order per kind", () => {
    const d = validateProfile({ sources: [], categoryOrder: { live: ["Kids", "kids", ""], bogus: ["x"] }, hiddenCategories: ["A", 1] });
    expect(d.categoryOrder).toEqual({ live: ["Kids"] });
    expect(d.hiddenCategories).toEqual(["A"]);
  });
});
