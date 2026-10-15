import { afterEach, describe, expect, it, vi } from "vitest";
import { sealJson, sha256Hex } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(5)));

/** One profile with two TVs (the lounge links, the bedroom reads) and a TV in another profile. */
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

const link = (env: Env, expiresIn = 3600) =>
  call(env, "lounge", "POST", "/api/device/realdebrid/link", {
    clientId: "cid", clientSecret: "csecret", accessToken: "a1", refreshToken: "r1", expiresIn, username: "arshad",
  });

/** Answers Real-Debrid's token endpoint with [status, body] and records the form posted. */
function mockRealDebrid(status: number, body: unknown) {
  const calls: { url: string; form: URLSearchParams }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    calls.push({ url, form: new URLSearchParams(String(init.body)) });
    return new Response(JSON.stringify(body), { status });
  });
  return calls;
}

afterEach(() => vi.unstubAllGlobals());

describe("real-debrid account per profile", () => {
  it("shares an account linked on one TV with every TV in the profile, and no other", async () => {
    const env = await setup();
    expect((await call(env, "bedroom", "GET", "/api/device/realdebrid/token")).status).toBe(404);
    expect((await link(env)).status).toBe(200);

    const res = await call(env, "bedroom", "GET", "/api/device/realdebrid/token");
    expect(await res.json()).toEqual({ accessToken: "a1", expiresAt: expect.any(Number), username: "arshad" });
    expect((await call(env, "away", "GET", "/api/device/realdebrid/token")).status).toBe(404);

    const config = await (await call(env, "bedroom", "GET", "/api/device/config")).json<{ realDebrid: unknown }>();
    expect(config.realDebrid).toEqual({ username: "arshad" });
    const other = await (await call(env, "away", "GET", "/api/device/config")).json<{ realDebrid: unknown }>();
    expect(other.realDebrid).toBeNull();
  });

  it("keeps the credentials encrypted", async () => {
    const env = await setup();
    await link(env);
    const row = await env.DB.prepare("SELECT data_enc FROM profile_debrid WHERE profile_id = 'home'").first<{ data_enc: string }>();
    expect(row!.data_enc).toMatch(/^v1\./);
    expect(row!.data_enc).not.toContain("csecret");
  });

  it("refreshes near expiry, keeps the new token for everyone, and keeps the old refresh token when none comes back", async () => {
    const env = await setup();
    await link(env, 60); // inside the refresh margin
    const calls = mockRealDebrid(200, { access_token: "a2", expires_in: 3600 });
    const res = await call(env, "bedroom", "GET", "/api/device/realdebrid/token");
    expect((await res.json<{ accessToken: string }>()).accessToken).toBe("a2");
    expect(calls[0].url).toBe("https://api.real-debrid.com/oauth/v2/token");
    expect(Object.fromEntries(calls[0].form)).toEqual({
      client_id: "cid", client_secret: "csecret", code: "r1", grant_type: "http://oauth.net/grant_type/device/1.0",
    });
    // Fresh now: the lounge gets a2 without another refresh.
    expect((await (await call(env, "lounge", "GET", "/api/device/realdebrid/token")).json<{ accessToken: string }>()).accessToken).toBe("a2");
    expect(calls).toHaveLength(1);
    // A refused token refreshes again, with the refresh token it still has.
    await call(env, "lounge", "POST", "/api/device/realdebrid/token", { rejected: "a2" });
    expect(calls[1].form.get("code")).toBe("r1");
  });

  it("doesn't refresh for a token another TV already replaced", async () => {
    const env = await setup();
    await link(env);
    const calls = mockRealDebrid(200, { access_token: "a2", expires_in: 3600 });
    const res = await call(env, "bedroom", "POST", "/api/device/realdebrid/token", { rejected: "an-old-token" });
    expect((await res.json<{ accessToken: string }>()).accessToken).toBe("a1");
    expect(calls).toHaveLength(0);
  });

  it("signs the profile out when Real-Debrid refuses the refresh, and on unlink", async () => {
    const env = await setup();
    await link(env, 60);
    mockRealDebrid(401, { error: "bad_token" });
    expect((await call(env, "bedroom", "GET", "/api/device/realdebrid/token")).status).toBe(410);
    expect((await call(env, "lounge", "GET", "/api/device/realdebrid/token")).status).toBe(404);

    await link(env);
    expect((await call(env, "bedroom", "POST", "/api/device/realdebrid/unlink", {})).status).toBe(200);
    expect((await call(env, "lounge", "GET", "/api/device/realdebrid/token")).status).toBe(404);
  });

  it("keeps an unexpired token when Real-Debrid is down", async () => {
    const env = await setup();
    await link(env, 300);
    mockRealDebrid(503, {});
    const res = await call(env, "bedroom", "GET", "/api/device/realdebrid/token");
    expect((await res.json<{ accessToken: string }>()).accessToken).toBe("a1");
  });

  it("refuses unpaired devices and incomplete credentials", async () => {
    const env = await setup();
    await env.DB.prepare("INSERT INTO devices (id, token_hash, created_at) VALUES ('new', ?, 0)").bind(await sha256Hex("new-token")).run();
    expect((await call(env, "new", "GET", "/api/device/realdebrid/token")).status).toBe(403);
    expect((await call(env, "lounge", "POST", "/api/device/realdebrid/link", { clientId: "cid" })).status).toBe(400);
  });
});
