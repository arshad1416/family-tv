import { describe, expect, it } from "vitest";
import { sealJson, sha256Hex } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));

async function setup(appVersion = "0.1.178") {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin" } as unknown as Env;
  await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES ('aa', 'aa', ?, 0, 0)").bind(await sealJson(KEY, { sources: [] })).run();
  await env.DB.prepare("INSERT INTO devices (id, token_hash, profile_id, nickname, app_version, lock_message, created_at) VALUES ('abc1', ?, 'aa', 'Lounge', ?, 'Homework first', 0)")
    .bind(await sha256Hex("rust-token"), appVersion)
    .run();
  return env;
}

const call = (env: Env, token: string | null, path: string, payload?: unknown, ip = "1.2.3.4") =>
  worker.fetch(
    new Request(`https://portal.test${path}`, {
      method: "POST",
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), "content-type": "application/json", "cf-connecting-ip": ip },
      body: JSON.stringify(payload ?? {}),
    }),
    env,
  );

const start = async (env: Env, local?: unknown) => (await (await call(env, "rust-token", "/api/device/transfer-start", { local })).json()) as { code: string };
const claim = (env: Env, code: string, token: string | null = null, ip?: string) => call(env, token, "/api/device/transfer-claim", { code }, ip);

describe("moving a TV from the Rust app to the Kotlin app", () => {
  it("hands the same device row, profile, name and lock to the Kotlin app, with its local settings", async () => {
    const env = await setup();
    const local = { v: 1, favourites: [{ kind: "live", sourceId: "s1", itemId: "42", name: "News", addedAt: 1 }], screensaver: { minutes: 10, source: "space" } };
    const { code } = await start(env, local);
    expect(code).toMatch(/^[a-f0-9]{32}$/);

    const res = await claim(env, code);
    expect(res.status).toBe(200);
    const got = await res.json<{ deviceId: string; deviceToken: string; local: unknown }>();
    expect(got.deviceId).toBe("abc1");
    expect(got.local).toEqual(local);

    const config = await (await worker.fetch(new Request("https://portal.test/api/device/config", { headers: { authorization: `Bearer ${got.deviceToken}` } }), env)).json<{ paired: boolean; nickname: string; lock: unknown }>();
    expect(config).toMatchObject({ paired: true, nickname: "Lounge", lock: { message: "Homework first" } });
    // The Rust app's token stops working.
    expect((await worker.fetch(new Request("https://portal.test/api/device/config", { headers: { authorization: "Bearer rust-token" } }), env)).status).toBe(401);
  });

  it("takes each code once, and keeps no settings once claimed", async () => {
    const env = await setup();
    const { code } = await start(env, { v: 1 });
    expect((await claim(env, code)).status).toBe(200);
    expect((await claim(env, code)).status).toBe(404);
    expect(await env.DB.prepare("SELECT count(*) AS n FROM device_transfers").first()).toEqual({ n: 0 });
  });

  it("forgets an earlier code when the TV asks again, and refuses an expired one", async () => {
    const env = await setup();
    const first = await start(env);
    const second = await start(env);
    expect((await claim(env, first.code)).status).toBe(404);
    await env.DB.prepare("UPDATE device_transfers SET expires = 0").run();
    expect((await claim(env, second.code)).status).toBe(404);
  });

  it("only moves a paired TV running the Rust app", async () => {
    expect((await call(await setup("0.2.92"), "rust-token", "/api/device/transfer-start")).status).toBe(409);
    const env = await setup();
    await env.DB.prepare("UPDATE devices SET profile_id = NULL").run();
    expect((await call(env, "rust-token", "/api/device/transfer-start")).status).toBe(409);
    expect((await call(env, "nobody", "/api/device/transfer-start")).status).toBe(401);
  });

  it("drops the Kotlin install's own unpaired registration when it claims", async () => {
    const env = await setup();
    await env.DB.prepare("INSERT INTO devices (id, token_hash, pair_code, created_at) VALUES ('new1', ?, 'ABCDEF', 0)").bind(await sha256Hex("kotlin-token")).run();
    const { code } = await start(env);
    expect((await claim(env, code, "kotlin-token")).status).toBe(200);
    expect((await env.DB.prepare("SELECT id FROM devices ORDER BY id").all()).results).toEqual([{ id: "abc1" }]);
  });

  it("locks out a caller after ten wrong codes", async () => {
    const env = await setup();
    for (let i = 0; i < 10; i++) expect((await claim(env, "0".repeat(32))).status).toBe(404);
    const { code } = await start(env);
    expect((await claim(env, code)).status).toBe(429);
    // Another caller is not held up.
    expect((await claim(env, code, null, "5.6.7.8")).status).toBe(200);
  });

  it("refuses settings that aren't an object or are too large", async () => {
    const env = await setup();
    expect((await call(env, "rust-token", "/api/device/transfer-start", { local: [1] })).status).toBe(400);
    expect((await call(env, "rust-token", "/api/device/transfer-start", { local: { x: "a".repeat(130 * 1024) } })).status).toBe(413);
  });
});

describe("moving into a Kotlin app that is already paired", () => {
  async function kotlin(env: Env, id: string, profile: string | null, nickname: string | null = null) {
    await env.DB.prepare("INSERT INTO devices (id, token_hash, profile_id, nickname, app_version, created_at) VALUES (?, ?, ?, ?, '0.2.93', 0)")
      .bind(id, await sha256Hex(`kt-${id}`), profile, nickname)
      .run();
  }
  const absorb = (env: Env, code: string, token: string, ip?: string) => call(env, token, "/api/device/transfer-absorb", { code }, ip);
  const status = async (env: Env, token: string) =>
    (await worker.fetch(new Request("https://portal.test/api/device/config", { headers: { authorization: `Bearer ${token}` } }), env)).status;

  it("drops the Rust app's row, so its token gets 401, and hands its settings to the Kotlin app", async () => {
    const env = await setup();
    await kotlin(env, "kt01", "aa");
    const local = { v: 1, viewers: [{ id: "v1", name: "Izaan", kids: true, favourites: [] }] };
    const { code } = await start(env, local);
    const res = await absorb(env, code, "kt-kt01");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ absorbed: "abc1", local });
    expect(await status(env, "rust-token")).toBe(401);
    // Its own setup stays; it had no name or lock, so it takes the Rust row's.
    const config = await (await worker.fetch(new Request("https://portal.test/api/device/config", { headers: { authorization: "Bearer kt-kt01" } }), env)).json<{ paired: boolean; nickname: string; lock: unknown }>();
    expect(config).toMatchObject({ paired: true, nickname: "Lounge", lock: { message: "Homework first" } });
    expect((await absorb(env, code, "kt-kt01")).status).toBe(404);
  });

  it("keeps the Kotlin app's own name", async () => {
    const env = await setup();
    await kotlin(env, "kt01", "aa", "Kitchen");
    const { code } = await start(env);
    expect((await absorb(env, code, "kt-kt01")).status).toBe(200);
    expect(await env.DB.prepare("SELECT nickname FROM devices WHERE id = 'kt01'").first()).toEqual({ nickname: "Kitchen" });
  });

  it("refuses a TV on another profile and leaves the Rust app paired", async () => {
    const env = await setup();
    await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES ('bb', 'bb', ?, 0, 0)").bind(await sealJson(KEY, { sources: [] })).run();
    await kotlin(env, "kt01", "bb");
    const { code } = await start(env);
    expect((await absorb(env, code, "kt-kt01")).status).toBe(409);
    expect(await status(env, "rust-token")).toBe(200);
  });

  it("is only for a paired Kotlin app, and wrong codes count toward the lockout", async () => {
    const env = await setup();
    await kotlin(env, "kt01", null);
    const { code } = await start(env);
    expect((await absorb(env, code, "kt-kt01")).status).toBe(409);
    expect((await absorb(env, code, "rust-token")).status).toBe(409);
    expect((await call(env, null, "/api/device/transfer-absorb", { code })).status).toBe(401);
    await kotlin(env, "kt02", "aa");
    for (let i = 0; i < 10; i++) expect((await absorb(env, "f".repeat(32), "kt-kt02", "9.9.9.9")).status).toBe(404);
    expect((await absorb(env, code, "kt-kt02", "9.9.9.9")).status).toBe(429);
    expect((await absorb(env, code, "kt-kt02")).status).toBe(200);
  });
});
