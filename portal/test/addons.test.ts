import { describe, expect, it } from "vitest";
import { ADMIN_PAGE } from "../src/admin-page";
import { addonStates, validateAddon, type AddonRow } from "../src/addons";
import { sealJson, sha256Hex } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));

/** Two profiles with a TV each (lounge on home, cottage on away) and an unpaired TV. */
async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin" } as unknown as Env;
  for (const id of ["aa", "bb"]) {
    await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES (?, ?, ?, 0, 0)").bind(id, id, await sealJson(KEY, { sources: [] })).run();
  }
  for (const [id, profile] of [["lounge", "aa"], ["cottage", "bb"], ["new", null]]) {
    await env.DB.prepare("INSERT INTO devices (id, token_hash, profile_id, created_at) VALUES (?, ?, ?, 0)").bind(id, await sha256Hex(`${id}-token`), profile).run();
  }
  return env;
}

const admin = (env: Env, method: string, path: string, payload?: unknown) =>
  worker.fetch(
    new Request(`https://portal.test${path}`, {
      method,
      headers: { authorization: "Bearer admin", "content-type": "application/json" },
      body: payload === undefined ? undefined : JSON.stringify(payload),
    }),
    env,
  );

const config = async (env: Env, device: string) =>
  (await (await worker.fetch(new Request("https://portal.test/api/device/config", { headers: { authorization: `Bearer ${device}-token` } }), env)).json()) as {
    addons: unknown[];
    commands: { type: string }[];
  };

const refreshes = async (env: Env, device: string) =>
  (await env.DB.prepare("SELECT COUNT(*) AS n FROM commands WHERE device_id = ? AND type = 'refresh'").bind(device).first<{ n: number }>())!.n;

describe("add-on states", () => {
  const addons: AddonRow[] = [
    { id: "multiview", name: "Multiview", description: "", status: "available" },
    { id: "recorder", name: "Recorder", description: "", status: "draft" },
    { id: "karaoke", name: "Karaoke", description: "", status: "retired" },
  ];

  it("leaves drafts out and offers what isn't owned", () => {
    expect(addonStates(addons, new Map(), true)).toEqual([
      { id: "multiview", name: "Multiview", state: "available" },
      { id: "karaoke", name: "Karaoke", state: "not_offered" },
    ]);
  });

  it("is active only while owned by a member; ownership survives a lapsed membership", () => {
    const owned = new Map([["multiview", "owned"], ["karaoke", "owned"]] as const);
    expect(addonStates(addons, owned, true).map((a) => a.state)).toEqual(["active", "active"]);
    expect(addonStates(addons, owned, false).map((a) => a.state)).toEqual(["paused", "paused"]);
  });

  it("never turns on a refunded or revoked add-on", () => {
    const gone = new Map([["multiview", "refunded"], ["karaoke", "revoked"]] as const);
    expect(addonStates(addons, gone, true).map((a) => a.state)).toEqual(["available", "not_offered"]);
  });

  it("checks what the admin page saves", () => {
    expect(validateAddon({ id: "multi-view", name: " Multiview ", status: "available" })).toEqual({ id: "multi-view", name: "Multiview", description: "", status: "available" });
    expect(validateAddon({ name: "Recorder" }, "recorder").status).toBe("draft");
    expect(() => validateAddon({ id: "Multi View", name: "x" })).toThrow(/id/);
    expect(() => validateAddon({ id: "multiview", name: "" })).toThrow(/name/);
    expect(() => validateAddon({ id: "multiview", name: "x", status: "free" })).toThrow(/status/);
  });
});

describe("add-ons on the portal", () => {
  it("sends TVs no add-ons until there are some", async () => {
    const env = await setup();
    expect((await config(env, "lounge")).addons).toEqual([]);
  });

  it("creates, grants and revokes, and the profile's TVs reload each time", async () => {
    const env = await setup();
    expect((await admin(env, "POST", "/api/admin/addons", { id: "multiview", name: "Multiview", status: "draft" })).status).toBe(200);
    expect((await config(env, "lounge")).addons).toEqual([]);
    expect(await refreshes(env, "lounge")).toBe(0);

    expect((await admin(env, "PUT", "/api/admin/addons/multiview", { name: "Multiview", status: "available" })).status).toBe(200);
    expect(await refreshes(env, "cottage")).toBe(1);
    expect((await config(env, "lounge")).addons).toEqual([{ id: "multiview", name: "Multiview", state: "available" }]);

    expect((await admin(env, "POST", "/api/admin/profiles/aa/addons/multiview", { owned: true })).status).toBe(200);
    expect((await config(env, "lounge")).addons).toEqual([{ id: "multiview", name: "Multiview", state: "active" }]);
    expect((await config(env, "cottage")).addons).toEqual([{ id: "multiview", name: "Multiview", state: "available" }]);
    expect(await refreshes(env, "lounge")).toBe(2);
    expect(await refreshes(env, "cottage")).toBe(1);

    await admin(env, "POST", "/api/admin/profiles/aa/addons/multiview", { owned: false });
    expect((await config(env, "lounge")).addons).toEqual([{ id: "multiview", name: "Multiview", state: "available" }]);
    const list = (await (await admin(env, "GET", "/api/admin/addons")).json()) as { purchases: { profile_id: string; status: string; source: string }[] };
    expect(list.purchases).toMatchObject([{ profile_id: "aa", status: "revoked", source: "manual" }]);
  });

  it("refuses bad input", async () => {
    const env = await setup();
    await admin(env, "POST", "/api/admin/addons", { id: "multiview", name: "Multiview" });
    expect((await admin(env, "POST", "/api/admin/addons", { id: "multiview", name: "Again" })).status).toBe(409);
    expect((await admin(env, "POST", "/api/admin/addons", { id: "x", name: "Too short" })).status).toBe(400);
    expect((await admin(env, "PUT", "/api/admin/addons/recorder", { name: "Recorder" })).status).toBe(404);
    expect((await admin(env, "POST", "/api/admin/profiles/aa/addons/multiview", { owned: "yes" })).status).toBe(400);
    expect((await admin(env, "POST", "/api/admin/profiles/cc/addons/multiview", { owned: true })).status).toBe(404);
    expect((await admin(env, "POST", "/api/admin/profiles/aa/addons/recorder", { owned: true })).status).toBe(404);
    const anon = await worker.fetch(new Request("https://portal.test/api/admin/addons"), env);
    expect(anon.status).toBe(401);
  });

  it("drops a profile's purchases with the profile", async () => {
    const env = await setup();
    await admin(env, "POST", "/api/admin/addons", { id: "multiview", name: "Multiview", status: "available" });
    await admin(env, "POST", "/api/admin/profiles/aa/addons/multiview", { owned: true });
    await admin(env, "DELETE", "/api/admin/profiles/aa");
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM addon_purchases").first<{ n: number }>())!.n).toBe(0);
  });

  it("serves an admin page whose script still parses", () => {
    const script = ADMIN_PAGE.match(/<script>([\s\S]*)<\/script>/)![1];
    expect(() => new Function(script)).not.toThrow();
  });
});

describe("before migration 0009", () => {
  it("still gives TVs their config, with no add-ons", async () => {
    const env = await setup();
    await env.DB.prepare("DROP TABLE addon_purchases").run();
    await env.DB.prepare("DROP TABLE addons").run();
    const res = await worker.fetch(new Request("https://portal.test/api/device/config", { headers: { authorization: "Bearer lounge-token" } }), env);
    expect(res.status).toBe(200);
    expect(((await res.json()) as { addons: unknown[] }).addons).toEqual([]);
  });
});
