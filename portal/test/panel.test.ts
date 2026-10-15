import { describe, expect, it } from "vitest";
import { openJson, sealJson } from "../src/crypto";
import worker, { type Env } from "../src/index";
import { MASK, type ProfileData } from "../src/model";
import { daysLeft, loginFromLineUrl, Panel, PanelError, type LineInfo } from "../src/panel";
import { memoryD1 } from "./d1";

const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));

/** A fetch that answers like the panel and records each call's query. */
function fakeFetch(answers: Record<string, unknown>, calls: URLSearchParams[] = []) {
  return (async (input: RequestInfo | URL) => {
    const q = new URL(String(input)).searchParams;
    calls.push(q);
    return new Response(JSON.stringify(answers[q.get("action")!] ?? { status: "false", result: "unknown action" }));
  }) as typeof fetch;
}

describe("Panel", () => {
  it("reads credits, packages and a line, sending the key on each call", async () => {
    const calls: URLSearchParams[] = [];
    const p = new Panel("k1", fakeFetch({
      reseller: { status: "true", username: "arshad", credits: "12", enabled: "1" },
      bouquet: [{ id: 3, name: "Canada + USA" }, { id: 9, name: "Full" }],
      device_info: [{ status: "true", expire: "2026-10-25", enabled: "1", user_id: 77, note: " Aiyaz ", max_connections: "2" }],
    }, calls));
    expect(await p.reseller()).toEqual({ username: "arshad", credits: 12, enabled: true });
    expect(await p.packages()).toEqual([{ id: "3", name: "Canada + USA" }, { id: "9", name: "Full" }]);
    expect(await p.line("u", "pw")).toEqual({ expire: "2026-10-25", enabled: true, userId: "77", note: "Aiyaz", maxConnections: 2 });
    expect(calls.every((q) => q.get("api_key") === "k1")).toBe(true);
    expect(calls[2].get("username")).toBe("u");
  });

  it("turns the panel's refusals into PanelError", async () => {
    const p = new Panel("k", fakeFetch({ device_info: { status: "false", result: "Line not found" } }));
    await expect(p.line("u", "p")).rejects.toThrow(new PanelError("Line not found"));
    await expect(p.renew("u", "p", 2)).rejects.toThrow(/months/);
  });
});

describe("Panel's default fetch", () => {
  it("calls the global fetch as a plain function, as Workers require", async () => {
    const real = globalThis.fetch;
    // Like workerd: fetch refuses to run as a method of some other object.
    globalThis.fetch = function (this: unknown) {
      if (this !== undefined && this !== globalThis) throw new TypeError("Illegal invocation");
      return Promise.resolve(new Response(JSON.stringify({ status: "true", username: "a", credits: "1", enabled: "1" })));
    } as typeof fetch;
    try {
      expect((await new Panel("k").reseller()).credits).toBe(1);
    } finally {
      globalThis.fetch = real;
    }
  });

  it("names why the panel was unreachable without quoting the key", async () => {
    const p = new Panel("secret-key", (async (u: RequestInfo | URL) => { throw new TypeError(`failed ${String(u)}`); }) as typeof fetch);
    await expect(p.reseller()).rejects.toThrow(/panel unreachable \(TypeError: failed .*api_key=…\)/);
  });
});

describe("line helpers", () => {
  it("splits a line link into server and login", () => {
    expect(loginFromLineUrl("http://srv.example:8080/get.php?username=ab&password=cd&type=m3u_plus")).toEqual({
      serverUrl: "http://srv.example:8080", username: "ab", password: "cd",
    });
    expect(loginFromLineUrl("not a url")).toBeNull();
  });
  it("counts days left", () => {
    expect(daysLeft("2026-10-25", "2026-10-15")).toBe(10);
    expect(daysLeft("2026-10-14", "2026-10-15")).toBe(-1);
  });
});

/** A panel with one known line; renewing adds a month, creating returns a new link. */
class StubPanel extends Panel {
  expire = "2026-10-20";
  renewed: string[] = [];
  constructor() {
    super("unused");
  }
  async line(username: string): Promise<LineInfo> {
    if (username === "gone") throw new PanelError("Line not found");
    return { expire: username === "new1" ? "2026-11-15" : this.expire, enabled: true, userId: "1", note: null, maxConnections: 1 };
  }
  async renew(username: string, _p: string, months: number) {
    this.renewed.push(`${username}:${months}`);
    this.expire = "2026-11-20";
  }
  async newLine() {
    return { userId: "2", url: "http://line.example:80/get.php?username=new1&password=pw1&type=m3u_plus" };
  }
}

async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin", PANEL: new StubPanel() } as unknown as Env & { PANEL: StubPanel };
  const data: ProfileData = {
    sources: [
      { id: "s1", type: "xtream", name: "Main", serverUrls: ["http://line.example"], username: "aiyaz", password: "secret" },
      { id: "s2", type: "xtream", name: "Old", serverUrls: ["http://line.example"], username: "gone", password: "x" },
      { id: "s3", type: "m3u", name: "List", m3uUrl: "http://list.example/a.m3u" },
    ],
  };
  await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES ('p1', 'Aiyaz', ?, 0, 0)").bind(await sealJson(KEY, data)).run();
  return env;
}

const call = (env: Env, path: string, init: RequestInit = {}) =>
  worker.fetch(new Request("https://portal.test" + path, { ...init, headers: { authorization: "Bearer admin", "content-type": "application/json" } }), env).then(async (r) => ({
    status: r.status,
    data: (await r.json()) as any,
  }));

describe("Lines API", () => {
  it("lists each Xtream login with days left, and the panel's error for a missing one", async () => {
    const env = await setup();
    const { data } = await call(env, "/api/admin/lines");
    expect(data.lines.map((l: any) => [l.username, l.expire, l.error])).toEqual([
      ["aiyaz", "2026-10-20", null],
      ["gone", null, "Line not found"],
    ]);
    expect(data.lines[0].password).toBeUndefined();
  });

  it("renews with the stored login and shows the new expiry", async () => {
    const env = await setup();
    await call(env, "/api/admin/lines");
    const r = await call(env, "/api/admin/lines/renew", { method: "POST", body: JSON.stringify({ username: "aiyaz", months: 1 }) });
    expect(r.status).toBe(200);
    expect(env.PANEL.renewed).toEqual(["aiyaz:1"]);
    expect((await call(env, "/api/admin/lines")).data.lines.find((l: any) => l.username === "aiyaz").expire).toBe("2026-11-20");
    expect((await call(env, "/api/admin/lines/renew", { method: "POST", body: JSON.stringify({ username: "nope", months: 1 }) })).status).toBe(404);
    expect((await call(env, "/api/admin/lines/renew", { method: "POST", body: JSON.stringify({ months: 1 }) })).status).toBe(400);
  });

  it("creates a line and adds it to the profile", async () => {
    const env = await setup();
    const r = await call(env, "/api/admin/lines/new", { method: "POST", body: JSON.stringify({ profileId: "p1", packageId: "3", months: 1, name: "Living room" }) });
    expect(r.data).toMatchObject({ ok: true, username: "new1" });
    const { data } = await call(env, "/api/admin/profiles");
    const src = data.profiles[0].data.sources.find((s: any) => s.username === "new1");
    expect(src).toMatchObject({ type: "xtream", name: "Living room", serverUrls: ["http://line.example"] });
    expect(src.password).not.toBe("pw1"); // masked for the page
    // The profile page adds the returned source to its unsaved copy; saving that keeps the line.
    expect(r.data.source).toMatchObject({ id: r.data.sourceId, username: "new1", password: MASK });
    expect((await call(env, "/api/admin/lines")).data.lines.find((l: any) => l.username === "new1").profiles).toEqual([{ id: "p1", name: "Aiyaz" }]);
  });

  it("creates a line without a profile; it is listed but no profile changes", async () => {
    const env = await setup();
    const r = await call(env, "/api/admin/lines/new", { method: "POST", body: JSON.stringify({ packageId: "3", months: 1, name: "Spare" }) });
    expect(r.data).toEqual({ ok: true, username: "new1" });
    const line = (await call(env, "/api/admin/lines")).data.lines.find((l: any) => l.username === "new1");
    expect(line).toMatchObject({ name: "Spare", profiles: [], serverUrls: ["http://line.example"], expire: "2026-11-15" });
    expect((await call(env, "/api/admin/profiles")).data.profiles[0].data.sources).toHaveLength(3);
  });

  it("adds lines that are already on the panel from their links, checking each first", async () => {
    const env = await setup();
    // No listing first: the profiles' lines still lend their server to a bare login.
    const r = await call(env, "/api/admin/lines/add", { method: "POST", body: JSON.stringify({ lines: [
      { name: "Grandma", link: "http://other.example:8080/get.php?username=gm1&password=pw&type=m3u_plus" },
      { link: "not a link" },
      { username: "gone", password: "x" },
      { username: "bare", password: "pw" },
    ] }) });
    expect(r.data.results.map((x: any) => [x.username, x.ok, x.error ?? null])).toEqual([
      ["gm1", true, null], [null, false, "not a playlist link with a username and password"], ["gone", false, "Line not found"], ["bare", true, null],
    ]);
    const lines = (await call(env, "/api/admin/lines")).data.lines;
    expect(lines.find((l: any) => l.username === "gm1")).toMatchObject({ name: "Grandma", serverUrls: ["http://other.example:8080"], profiles: [], expire: "2026-10-20" });
    // Without a link or server, a line takes the server most lines use.
    expect(lines.find((l: any) => l.username === "bare")).toMatchObject({ name: "bare", serverUrls: ["http://line.example"] });
    expect(lines.map((l: any) => l.password)).toEqual([undefined, undefined, undefined, undefined]);
  });

  it("removes a line from the list only when no profile uses it", async () => {
    const env = await setup();
    await call(env, "/api/admin/lines");
    await call(env, "/api/admin/lines/add", { method: "POST", body: JSON.stringify({ lines: [{ link: "http://line.example/get.php?username=gm1&password=pw" }] }) });
    expect((await call(env, "/api/admin/lines/forget", { method: "POST", body: JSON.stringify({ username: "aiyaz" }) })).status).toBe(409);
    expect((await call(env, "/api/admin/lines/forget", { method: "POST", body: JSON.stringify({ username: "gm1" }) })).status).toBe(200);
    expect((await call(env, "/api/admin/lines")).data.lines.map((l: any) => l.username)).toEqual(["aiyaz", "gone"]);
  });

  it("copies a line picked on the profile page, password included, only when the profile is saved", async () => {
    const env = await setup();
    const picked = { id: "n1", type: "xtream", name: "Main", serverUrls: ["http://line.example"], username: "aiyaz", password: MASK, copyOf: "aiyaz" };
    const r = await call(env, "/api/admin/profiles", { method: "POST", body: JSON.stringify({ name: "Grandma", data: { sources: [picked] } }) });
    expect(r.status).toBe(200);
    const row = await env.DB.prepare("SELECT data_enc FROM profiles WHERE id = ?").bind(r.data.id).first<{ data_enc: string }>();
    const stored = await openJson<ProfileData>(KEY, row!.data_enc);
    expect(stored.sources[0]).toEqual({ id: "n1", type: "xtream", name: "Main", serverUrls: ["http://line.example"], username: "aiyaz", password: "secret" });
    // A line that isn't on the Lines page can't be copied.
    const gone = await call(env, "/api/admin/profiles", { method: "POST", body: JSON.stringify({ name: "X", data: { sources: [{ ...picked, copyOf: "nope" }] } }) });
    expect(gone.status).toBe(409);
    // A line added on the Lines page (in no profile) can be.
    await call(env, "/api/admin/lines/add", { method: "POST", body: JSON.stringify({ lines: [{ link: "http://line.example/get.php?username=gm1&password=pw9" }] }) });
    const r2 = await call(env, "/api/admin/profiles", { method: "POST", body: JSON.stringify({ name: "Y", data: { sources: [{ ...picked, username: "gm1", copyOf: "gm1" }] } }) });
    const row2 = await env.DB.prepare("SELECT data_enc FROM profiles WHERE id = ?").bind(r2.data.id).first<{ data_enc: string }>();
    expect((await openJson<ProfileData>(KEY, row2!.data_enc)).sources[0].password).toBe("pw9");
  });

  it("says the panel isn't set up without a key", async () => {
    const env = { ...(await setup()), PANEL: undefined } as unknown as Env;
    expect((await call(env, "/api/admin/panel")).data).toEqual({ configured: false });
    expect((await call(env, "/api/admin/lines/renew", { method: "POST", body: JSON.stringify({ username: "aiyaz", months: 1 }) })).status).toBe(404);
  });
});
