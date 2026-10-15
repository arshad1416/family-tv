import { afterEach, describe, expect, it, vi } from "vitest";
import worker, { type Env } from "../src/index";

// Through the Worker's fetch, so thrown HttpErrors become responses as they do in production.
const handle = (req: Request, e: Env) => worker.fetch(req, e);

const SECRET = "shh-client-secret";

/** Just enough D1 for requireDevice and deviceConfig: one device row, no profile, no commands. */
function fakeDb(device: Record<string, unknown> | null) {
  const stmt = (sql: string) => ({
    bind: () => stmt(sql),
    first: async () => (sql.includes("token_hash") ? device : null),
    run: async () => ({}),
    all: async () => ({ results: [] }),
  });
  return { prepare: stmt, batch: async () => [] } as unknown as D1Database;
}

const paired = { id: "d1", profile_id: "p1", nickname: "Lounge" };
const unpaired = { id: "d2", profile_id: null, pair_code: "ABC123", pair_expires: Date.now() + 60_000 };

const env = (device: Record<string, unknown> | null = paired, configured = true) =>
  ({
    DB: fakeDb(device),
    CONFIG_KEY: "unused",
    ...(configured ? { TRAKT_CLIENT_ID: "client-123", TRAKT_CLIENT_SECRET: SECRET } : {}),
  }) as unknown as Env;

const post = (path: string, payload: unknown = {}, token = "device-token") =>
  new Request(`https://portal.test${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify(payload),
  });

/** Records calls to api.trakt.tv and answers with [status, body]. */
function mockTrakt(status: number, body: unknown) {
  const calls: { url: string; headers: Headers; body: Record<string, string> }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    calls.push({ url, headers: new Headers(init.headers), body: JSON.parse(String(init.body)) });
    return new Response(JSON.stringify(body), { status });
  });
  return calls;
}

afterEach(() => vi.unstubAllGlobals());

describe("trakt token proxy", () => {
  it("starts a device-code login with the client id only", async () => {
    const calls = mockTrakt(200, { device_code: "dc", user_code: "ABCD1234", verification_url: "https://trakt.tv/activate", expires_in: 600, interval: 5 });
    const res = await handle(post("/api/device/trakt/code"), env());
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: 200, data: expect.objectContaining({ user_code: "ABCD1234" }) });
    expect(calls[0].url).toBe("https://api.trakt.tv/oauth/device/code");
    expect(calls[0].body).toEqual({ client_id: "client-123" });
    expect(calls[0].headers.get("trakt-api-key")).toBe("client-123");
  });

  it("adds the secret to the token exchange and passes Trakt's status through", async () => {
    const calls = mockTrakt(400, {});
    const res = await handle(post("/api/device/trakt/token", { code: "dc" }), env());
    expect(res.status).toBe(200); // not the TV's own auth failing
    expect(await res.json()).toEqual({ status: 400, data: {} });
    expect(calls[0].url).toBe("https://api.trakt.tv/oauth/device/token");
    expect(calls[0].body).toEqual({ code: "dc", client_id: "client-123", client_secret: SECRET });
  });

  it("refreshes with the registered redirect URI", async () => {
    const calls = mockTrakt(200, { access_token: "a2", refresh_token: "r2", expires_in: 86400, created_at: 1 });
    const res = await handle(post("/api/device/trakt/refresh", { refreshToken: "r1" }), env());
    const out = await res.text();
    expect(out).toContain("a2");
    expect(out).not.toContain(SECRET);
    expect(calls[0].url).toBe("https://api.trakt.tv/oauth/token");
    expect(calls[0].body).toMatchObject({ refresh_token: "r1", grant_type: "refresh_token", redirect_uri: "https://portal.test/trakt", client_secret: SECRET });
  });

  it("revokes a token on disconnect", async () => {
    const calls = mockTrakt(200, {});
    await handle(post("/api/device/trakt/revoke", { token: "a1" }), env());
    expect(calls[0].url).toBe("https://api.trakt.tv/oauth/revoke");
    expect(calls[0].body).toMatchObject({ token: "a1", client_secret: SECRET });
  });

  it("rejects missing fields, unknown devices, unpaired devices and an unconfigured portal", async () => {
    const calls = mockTrakt(200, {});
    expect((await handle(post("/api/device/trakt/token", {}), env())).status).toBe(400);
    expect((await handle(post("/api/device/trakt/code", {}, ""), env())).status).toBe(401);
    expect((await handle(post("/api/device/trakt/code"), env(null))).status).toBe(401);
    expect((await handle(post("/api/device/trakt/code"), env(unpaired))).status).toBe(403);
    expect((await handle(post("/api/device/trakt/code"), env(paired, false))).status).toBe(404);
    expect(calls).toHaveLength(0);
  });
});

describe("device config", () => {
  const get = () => new Request("https://portal.test/api/device/config", { headers: { authorization: "Bearer device-token" } });

  it("hands paired TVs the Trakt client id but never the secret", async () => {
    const res = await handle(get(), env());
    const text = await res.text();
    expect(JSON.parse(text).trakt).toEqual({ clientId: "client-123" });
    expect(text).not.toContain(SECRET);
  });

  it("says Trakt is off when the secrets are not set", async () => {
    const body = (await (await handle(get(), env(paired, false))).json()) as { trakt: unknown };
    expect(body.trakt).toBeNull();
  });
});

describe("trakt redirect URI", () => {
  it("uses the configured URI, else this portal's /trakt", async () => {
    const calls = mockTrakt(200, {});
    const e = { ...env(), TRAKT_REDIRECT_URI: "https://familytv.example/trakt" } as Env;
    await handle(post("/api/device/trakt/refresh", { refreshToken: "r1" }), e);
    expect(calls[0].body.redirect_uri).toBe("https://familytv.example/trakt");
  });
});
