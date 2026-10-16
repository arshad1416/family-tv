import { afterEach, describe, expect, it, vi } from "vitest";
import { openJson, sealJson, sha256Hex } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { memoryD1 } from "./d1";

const CONFIG_KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(11)));
const FAKE_KEY = "fixture-only-not-a-real-rd-key-123";
const OTHER_KEY = "fixture-only-replacement-rd-key-456";
async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY, ADMIN_TOKEN: "admin" } as Env;
  for (const profile of ["ab12", "cd34"]) await env.DB.prepare("INSERT INTO profiles (id,name,data_enc,created_at,updated_at) VALUES (?,?,?,0,0)")
    .bind(profile, profile, await sealJson(CONFIG_KEY, { sources: [] })).run();
  for (const [id, profile] of [["lounge", "ab12"], ["bedroom", "ab12"], ["away", "cd34"], ["new", null]]) {
    await env.DB.prepare("INSERT INTO devices (id,token_hash,profile_id,created_at) VALUES (?,?,?,0)").bind(id, await sha256Hex(id + "-token"), profile).run();
  }
  return env;
}
const request = (env: Env, device: string, method = "GET", value?: unknown, profile = "ab12") => worker.fetch(new Request(
  `https://portal.test/api/device/realdebrid/api-key${method === "GET" ? "?profileId=" + profile : ""}`, {
    method, headers: { authorization: "Bearer " + device + "-token", "content-type": "application/json" },
    body: value === undefined ? undefined : JSON.stringify(value),
  }), env);
const put = (env: Env, revision: number, apiKey = FAKE_KEY, device = "lounge", profileId = "ab12") => request(env, device, "PUT", { profileId, revision, apiKey });
const remove = (env: Env, revision: number) => request(env, "lounge", "DELETE", { profileId: "ab12", revision });
afterEach(() => vi.restoreAllMocks());

describe("profile API keys (synthetic fixtures only)", () => {
  it("requires HTTPS for reads and mutations before touching credentials", async () => {
    const env = await setup();
    await put(env, 0);
    for (const method of ["GET", "PUT", "DELETE"]) {
      const res = await worker.fetch(new Request("http://portal.test/api/device/realdebrid/api-key?profileId=ab12", {
        method, headers: { authorization: "Bearer lounge-token", "content-type": "application/json" },
        body: method === "GET" ? undefined : JSON.stringify({ profileId: "ab12", revision: 1, apiKey: OTHER_KEY }),
      }), env);
      expect(res.status).toBe(426);
      expect(await res.json()).toEqual({ error: "HTTPS required" });
    }
    expect(await (await request(env, "bedroom")).json()).toMatchObject({ revision: 1, apiKey: FAKE_KEY });
  });
  it("returns absent revision zero, shares only within the paired profile, and never caches", async () => {
    const env = await setup();
    expect(await (await request(env, "bedroom")).json()).toEqual({ profileId: "ab12", revision: 0, configured: false, apiKey: null });
    const saved = await put(env, 0);
    expect(await saved.json()).toEqual({ profileId: "ab12", revision: 1, configured: true });
    const got = await request(env, "bedroom");
    expect(got.headers.get("cache-control")).toBe("no-store");
    expect(await got.json()).toEqual({ profileId: "ab12", revision: 1, configured: true, apiKey: FAKE_KEY });
    expect((await request(env, "away")).status).toBe(403);
    expect(await (await request(env, "away", "GET", undefined, "cd34")).json()).toMatchObject({ configured: false, apiKey: null });
  });

  it("rejects unknown, admin, unpaired and wrong expected profile tokens for every operation", async () => {
    const env = await setup();
    for (const device of ["unknown", "new", "away"]) for (const method of ["GET", "PUT", "DELETE"]) {
      const r = await request(env, device, method, method === "GET" ? undefined : { profileId: "ab12", revision: 0, apiKey: FAKE_KEY });
      expect(r.status).toBe(device === "unknown" ? 401 : 403);
    }
    expect((await worker.fetch(new Request("https://portal.test/api/device/realdebrid/api-key?profileId=ab12", { headers: { authorization: "Bearer admin" } }), env)).status).toBe(401);
    expect((await put(env, 0, FAKE_KEY, "lounge", "cd34")).status).toBe(403);
    expect((await request(env, "lounge", "GET", undefined, "")).status).toBe(403);
  });

  it("encrypts a profile-bound envelope and keeps keys out of routine config/admin/heartbeat", async () => {
    const env = await setup();
    await put(env, 0);
    const row = await env.DB.prepare("SELECT key_enc FROM profile_rd_api_keys WHERE profile_id='ab12'").first<{ key_enc: string }>();
    expect(row!.key_enc).not.toContain(FAKE_KEY);
    expect(await openJson(CONFIG_KEY, row!.key_enc)).toEqual({ profileId: "ab12", apiKey: FAKE_KEY });
    for (const path of ["/api/device/config", "/api/admin/profiles", "/api/admin/devices"]) {
      const response = await worker.fetch(new Request("https://portal.test" + path, { headers: { authorization: "Bearer " + (path.includes("admin") ? "admin" : "lounge-token") } }), env);
      expect(response.status).toBe(200);
      expect(await response.text()).not.toContain(FAKE_KEY);
    }
    const heartbeat = await worker.fetch(new Request("https://portal.test/api/device/heartbeat", { method: "POST", headers: { authorization: "Bearer lounge-token" }, body: "{}" }), env);
    expect(await heartbeat.text()).not.toContain(FAKE_KEY);
    await env.DB.prepare("INSERT INTO profile_rd_api_keys(profile_id,revision,key_enc,updated_at) VALUES ('cd34',1,?,0)").bind(row!.key_enc).run();
    const swapped = await request(env, "away", "GET", undefined, "cd34");
    expect(swapped.status).toBe(500);
    expect(await swapped.text()).not.toContain(FAKE_KEY);
  });

  it("keeps remove tombstones; stale replace/remove cannot overwrite later decisions", async () => {
    const env = await setup();
    await put(env, 0);
    expect(await (await remove(env, 1)).json()).toEqual({ profileId: "ab12", revision: 2, configured: false });
    expect((await put(env, 1)).status).toBe(409);
    expect((await put(env, 0)).status).toBe(409);
    expect(await (await put(env, 2, OTHER_KEY)).json()).toMatchObject({ revision: 3 });
    expect((await remove(env, 2)).status).toBe(409);
    expect(await (await request(env, "bedroom")).json()).toMatchObject({ revision: 3, apiKey: OTHER_KEY });
    const fresh = await setup();
    expect(await (await remove(fresh, 0)).json()).toMatchObject({ revision: 1, configured: false });
    expect((await put(fresh, 0)).status).toBe(409);
  });

  it("allows only one concurrent update at a revision", async () => {
    const env = await setup();
    const replies = await Promise.all([put(env, 0), put(env, 0, OTHER_KEY, "bedroom")]);
    expect(replies.map(r => r.status).sort()).toEqual([200, 409]);
  });

  it("rejects reassignment or token removal while encryption awaits", async () => {
    for (const mutation of ["UPDATE devices SET profile_id='cd34' WHERE id='lounge'", "DELETE FROM devices WHERE id='lounge'"]) {
      const env = await setup();
      const encrypt = crypto.subtle.encrypt.bind(crypto.subtle);
      vi.spyOn(crypto.subtle, "encrypt").mockImplementationOnce(async (...args) => {
        const encrypted = await encrypt(...args);
        await env.DB.prepare(mutation).run();
        return encrypted;
      });
      expect((await put(env, 0)).status).toBe(403);
      expect(await env.DB.prepare("SELECT * FROM profile_rd_api_keys").first()).toBeNull();
      vi.restoreAllMocks();
    }
  });

  it("does not return decrypted keys if membership or revision changes during decrypt", async () => {
    for (const mutation of ["UPDATE devices SET profile_id='cd34' WHERE id='lounge'", "UPDATE profile_rd_api_keys SET revision=2,key_enc=NULL WHERE profile_id='ab12'"]) {
      const env = await setup();
      await put(env, 0);
      const decrypt = crypto.subtle.decrypt.bind(crypto.subtle);
      vi.spyOn(crypto.subtle, "decrypt").mockImplementationOnce(async (...args) => {
        const decrypted = await decrypt(...args);
        await env.DB.prepare(mutation).run();
        return decrypted;
      });
      const response = await request(env, "lounge");
      expect([403, 409]).toContain(response.status);
      expect(await response.text()).not.toContain(FAKE_KEY);
      vi.restoreAllMocks();
    }
  });

  it("validates revisions and keys without echoing secrets or calling upstream", async () => {
    const env = await setup();
    const fetch = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("No network allowed"));
    for (const value of ["short", "spaces are invalid here", "x".repeat(513), "é".repeat(32), null, 42]) {
      const response = await request(env, "lounge", "PUT", { profileId: "ab12", revision: 0, apiKey: value });
      expect(response.status).toBe(400);
      expect(await response.text()).not.toContain(FAKE_KEY);
    }
    for (const revision of [-1, 0.5, "0", Number.MAX_SAFE_INTEGER]) expect((await request(env, "lounge", "DELETE", { profileId: "ab12", revision })).status).toBe(400);
    await put(env, 0);
    expect(fetch).not.toHaveBeenCalled();
  });
});

const adminKey = (env: Env, method = "GET", value?: unknown, profile = "ab12", token = "admin", protocol = "https") => worker.fetch(new Request(
  `${protocol}://portal.test/api/admin/profiles/${profile}/realdebrid/api-key`, {
    method, headers: { authorization: "Bearer " + token, "content-type": "application/json" },
    body: value === undefined ? undefined : JSON.stringify(value),
  }), env);
describe("portal API key control", () => {
  it("only permits admin, returns status without decrypting, and isolates path profile", async () => {
    const env = await setup();
    for (const token of ["", "unknown", "lounge-token"]) for (const method of ["GET", "PUT", "DELETE"]) expect((await adminKey(env, method, method === "GET" ? undefined : { revision: 0, apiKey: FAKE_KEY }, "ab12", token)).status).toBe(401);
    expect(await (await adminKey(env)).json()).toEqual({ profileId: "ab12", revision: 0, configured: false });
    expect(await (await adminKey(env, "PUT", { revision: 0, apiKey: FAKE_KEY, profileId: "cd34" })).json()).toEqual({ profileId: "ab12", revision: 1, configured: true });
    const decrypt = vi.spyOn(crypto.subtle, "decrypt").mockRejectedValue(new Error("no decrypt"));
    const status = await adminKey(env); expect(status.headers.get("cache-control")).toBe("no-store");
    expect(await status.json()).toEqual({ profileId: "ab12", revision: 1, configured: true }); expect(decrypt).not.toHaveBeenCalled();
    expect(await (await adminKey(env, "GET", undefined, "cd34")).json()).toEqual({ profileId: "cd34", revision: 0, configured: false });
    vi.restoreAllMocks(); expect(await (await request(env, "bedroom")).json()).toMatchObject({ apiKey: FAKE_KEY });
  });
  it("shares revision CAS and removal tombstones with devices", async () => {
    const env = await setup(); await put(env, 0);
    expect((await adminKey(env, "PUT", { revision: 0, apiKey: OTHER_KEY })).status).toBe(409);
    expect(await (await adminKey(env, "DELETE", { revision: 1 })).json()).toEqual({ profileId: "ab12", revision: 2, configured: false });
    expect((await put(env, 1)).status).toBe(409); expect((await adminKey(env, "DELETE", { revision: 1 })).status).toBe(409);
    expect((await Promise.all([adminKey(env, "PUT", { revision: 2, apiKey: OTHER_KEY }), put(env, 2)])).map(r => r.status).sort()).toEqual([200,409]);
  });
  it("rejects missing profiles, insecure transport, invalid input and sanitizes crypto errors", async () => {
    const env = await setup(); const log = vi.spyOn(console, "error").mockImplementation(() => {});
    const network = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("no network"));
    for (const method of ["GET", "PUT", "DELETE"]) {
      const value = method === "GET" ? undefined : { revision: 0, apiKey: FAKE_KEY };
      expect((await adminKey(env, method, value, "ffff")).status).toBe(404);
      expect((await adminKey(env, method, value, "ab12", "admin", "http")).status).toBe(426);
    }
    for (const apiKey of ["short", "spaces are invalid", null, 123]) expect((await adminKey(env,"PUT",{revision:0,apiKey})).status).toBe(400);
    for (const revision of [-1,"0",0.5,Number.MAX_SAFE_INTEGER]) expect((await adminKey(env,"DELETE",{revision})).status).toBe(400);
    vi.spyOn(crypto.subtle,"encrypt").mockRejectedValue(new Error(FAKE_KEY));
    const failed = await adminKey(env,"PUT",{revision:0,apiKey:FAKE_KEY}); expect(failed.status).toBe(500);
    expect(await failed.text()).toBe('{"error":"credential unavailable"}'); expect(log).not.toHaveBeenCalled(); expect(network).not.toHaveBeenCalled();
  });
  it("does not create credentials if profile is deleted during encryption",async()=>{
    const env=await setup();const encrypt=crypto.subtle.encrypt.bind(crypto.subtle);
    vi.spyOn(crypto.subtle,"encrypt").mockImplementationOnce(async(...args)=>{const result=await encrypt(...args);await env.DB.prepare("DELETE FROM profiles WHERE id='ab12'").run();return result;});
    expect((await adminKey(env,"PUT",{revision:0,apiKey:FAKE_KEY})).status).toBe(404);
    expect(await env.DB.prepare("SELECT * FROM profile_rd_api_keys").first()).toBeNull();
  });
});
