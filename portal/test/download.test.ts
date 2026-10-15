import { describe, expect, it } from "vitest";
import { handle, latestApp, subtitlesApiKey, tmdbApiKey, tvdbApiKey, type Env } from "../src/index";

const apk = new Uint8Array([0x50, 0x4b, 3, 4]);
const bucket = (has: boolean, keys = ["family-tv.apk"]) =>
  ({ get: async (key: string) => (has && keys.includes(key) ? { size: apk.length, body: new Blob([apk]).stream() } : null) }) as unknown as R2Bucket;
const env = (apps?: R2Bucket) => ({ APPS: apps }) as unknown as Env;

describe("app download", () => {
  it("serves the uploaded APK at /tv", async () => {
    const res = await handle(new Request("https://portal.test/tv"), env(bucket(true)));
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("application/vnd.android.package-archive");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(apk);
  });

  it("serves the Rust build at /tvrust, and only that file", async () => {
    const res = await handle(new Request("https://portal.test/tvrust"), env(bucket(true, ["family-tv-rust.apk"])));
    expect(res.status).toBe(200);
    expect(res.headers.get("content-disposition")).toBe('attachment; filename="family-tv-rust.apk"');
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(apk);
    expect((await handle(new Request("https://portal.test/tvrust/"), env(bucket(true)))).status).toBe(404);
    expect((await handle(new Request("https://portal.test/tv"), env(bucket(true, ["family-tv-rust.apk"])))).status).toBe(404);
  });

  it("serves the Rust build's own update feed, 404 until one is published", async () => {
    const feed = '{"versionCode":14,"versionName":"0.1.14","url":"https://familytv.example/tvrust"}';
    const apps = { get: async (key: string) => (key === "family-tv-rust-latest.json" ? { body: new Blob([feed]).stream() } : null) } as unknown as R2Bucket;
    const res = await handle(new Request("https://portal.test/tvrust/latest.json"), env(apps));
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("application/json");
    expect(await res.text()).toBe(feed);
    expect((await handle(new Request("https://portal.test/tvrust/latest.json"), env(bucket(true)))).status).toBe(404);
  });

  it("404s before an APK is uploaded or without the bucket", async () => {
    expect((await handle(new Request("https://portal.test/app.apk"), env(bucket(false)))).status).toBe(404);
    expect((await handle(new Request("https://portal.test/tv"), env())).status).toBe(404);
  });
});

describe("latestApp", () => {
  const withLatest = (body: string | null) =>
    ({ get: async (key: string) => (key === "latest.json" && body ? { json: async () => JSON.parse(body) } : null) }) as unknown as R2Bucket;

  it("offers the CI-published build at the portal's /tv", async () => {
    const r = await latestApp({ APPS: withLatest('{"versionCode":105,"versionName":"0.2.5"}') } as unknown as Env, new Request("https://familytv.example/api/device/config"));
    expect(r).toEqual({ versionCode: 105, versionName: "0.2.5", url: "https://familytv.example/tv" });
  });

  it("falls back to wrangler vars, or nothing", async () => {
    const req = new Request("https://p.test/");
    expect(await latestApp({ APPS: withLatest(null) } as unknown as Env, req)).toBeNull();
    const vars = { APPS: withLatest(null), LATEST_APP_VERSION_CODE: "3", LATEST_APP_URL: "https://x/a.apk" } as unknown as Env;
    expect(await latestApp(vars, req)).toEqual({ versionCode: 3, versionName: null, url: "https://x/a.apk" });
  });
});

describe("subtitlesApiKey", () => {
  it("passes the secret through, trimmed", () =>
    expect(subtitlesApiKey({ OPENSUBTITLES_API_KEY: " abc123 " } as unknown as Env)).toBe("abc123"));
  it("is null when the secret is unset or blank", () => {
    expect(subtitlesApiKey({} as unknown as Env)).toBeNull();
    expect(subtitlesApiKey({ OPENSUBTITLES_API_KEY: "  " } as unknown as Env)).toBeNull();
  });
});

describe("tmdbApiKey and tvdbApiKey", () => {
  it("pass their secrets through, trimmed", () => {
    expect(tmdbApiKey({ TMDB_API_KEY: " tmdb123 " } as unknown as Env)).toBe("tmdb123");
    expect(tvdbApiKey({ TVDB_API_KEY: "tvdb-key\n" } as unknown as Env)).toBe("tvdb-key");
  });
  it("are null when unset or blank, and independent of each other", () => {
    expect(tmdbApiKey({ TVDB_API_KEY: "x" } as unknown as Env)).toBeNull();
    expect(tmdbApiKey({ TMDB_API_KEY: " " } as unknown as Env)).toBeNull();
    expect(tvdbApiKey({ TMDB_API_KEY: "x" } as unknown as Env)).toBeNull();
  });
});
