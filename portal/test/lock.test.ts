import { describe, expect, it } from "vitest";
import { sealJson, sha256Hex } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { DEFAULT_LOCK_MESSAGE } from "../src/model";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));

async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin" } as unknown as Env;
  await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES ('aa', 'aa', ?, 0, 0)").bind(await sealJson(KEY, { sources: [] })).run();
  await env.DB.prepare("INSERT INTO devices (id, token_hash, profile_id, created_at) VALUES ('abc1', ?, 'aa', 0)").bind(await sha256Hex("tv-token")).run();
  return env;
}

const call = (env: Env, token: string, method: string, path: string, payload?: unknown) =>
  worker.fetch(
    new Request(`https://portal.test${path}`, {
      method,
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: payload === undefined ? undefined : JSON.stringify(payload),
    }),
    env,
  );
const lockOf = async (env: Env) => ({
  config: (await (await call(env, "tv-token", "GET", "/api/device/config")).json<{ lock: unknown }>()).lock,
  heartbeat: (await (await call(env, "tv-token", "POST", "/api/device/heartbeat", {})).json<{ lock: unknown }>()).lock,
});

describe("Lock this TV", () => {
  it("tells the TV it is locked, with the admin's message or the default, until unlocked", async () => {
    const env = await setup();
    expect(await lockOf(env)).toEqual({ config: null, heartbeat: null });

    expect((await call(env, "admin", "PATCH", "/api/admin/devices/abc1", { locked: true, lockMessage: "  Homework first  " })).status).toBe(200);
    expect(await lockOf(env)).toEqual({ config: { message: "Homework first" }, heartbeat: { message: "Homework first" } });
    const listed = await (await call(env, "admin", "GET", "/api/admin/devices")).json<{ devices: { lock_message: string }[] }>();
    expect(listed.devices[0].lock_message).toBe("Homework first");

    await call(env, "admin", "PATCH", "/api/admin/devices/abc1", { locked: true, lockMessage: "" });
    expect((await lockOf(env)).config).toEqual({ message: DEFAULT_LOCK_MESSAGE });

    await call(env, "admin", "PATCH", "/api/admin/devices/abc1", { locked: false });
    expect(await lockOf(env)).toEqual({ config: null, heartbeat: null });
  });

  it("leaves the lock alone when only the name or profile changes", async () => {
    const env = await setup();
    await call(env, "admin", "PATCH", "/api/admin/devices/abc1", { locked: true });
    await call(env, "admin", "PATCH", "/api/admin/devices/abc1", { nickname: "Lounge" });
    expect((await lockOf(env)).config).toEqual({ message: DEFAULT_LOCK_MESSAGE });
  });

  it("queues Uninstall for the TV like any other action", async () => {
    const env = await setup();
    expect((await call(env, "admin", "POST", "/api/admin/devices/abc1/commands", { type: "uninstall" })).status).toBe(200);
    const hb = await (await call(env, "tv-token", "POST", "/api/device/heartbeat", {})).json<{ commands: { type: string }[] }>();
    expect(hb.commands.map((c) => c.type)).toEqual(["uninstall"]);
  });
});
