import { openJson, pairCode, randomId, sealJson, sha256Hex, timingSafeEqual } from "./crypto";
import { assertHttpUrl, CATEGORY_KINDS, cleanNames, forDevice, MASK, maskSecrets, mergeSecrets, profileHosts, validateProfile, VERDICTS, type ProfileData, type Source } from "./model";
import { probe } from "./reachability";
import { ADMIN_PAGE } from "./admin-page";
import { preferServer, suggestions } from "./advice";
import { daysLeft, loginFromLineUrl, Panel, PanelError, RENEW_MONTHS } from "./panel";
import { USAGE_APPS, USAGE_BATCH_MAX, USAGE_KEEP_MS, usageEvent } from "./usage";

export interface Env {
  DB: D1Database;
  ADMIN_TOKEN: string;
  CONFIG_KEY: string;
  LATEST_APP_VERSION_CODE?: string;
  LATEST_APP_URL?: string;
  APPS?: R2Bucket;
  /** Trakt app credentials (wrangler secret put). Without both, the TV hides its Trakt section. */
  TRAKT_CLIENT_ID?: string;
  TRAKT_CLIENT_SECRET?: string;
  /** The Trakt app's Redirect URI (wrangler.toml var); defaults to <this portal>/trakt. */
  TRAKT_REDIRECT_URI?: string;
  /** Optional secret: OpenSubtitles.com API key, delivered only to paired TVs for online subtitles. */
  OPENSUBTITLES_API_KEY?: string;
  /** Optional secret: TMDB API key (v3 key or v4 read token), delivered only to paired TVs for trailers and episode details. */
  TMDB_API_KEY?: string;
  /** Optional secret: TheTVDB v4 API key, delivered only to paired TVs as a fallback after TMDB. */
  TVDB_API_KEY?: string;
  /** Optional secret: the 8K Gold reseller panel API key; enables the Lines page (credits, expiry, renew, new lines). */
  IPTV_PANEL_API_KEY?: string;
  /** Tests only: stands in for the panel. */
  PANEL?: Panel;
}

const APK_KEY = "family-tv.apk";
/** The Rust rewrite (arshad1416/family-tv-rust): its own package, installed next to this app. */
const RUST_APK_KEY = "family-tv-rust.apk";
/** The Rust build's own update feed, written by its CI; the Kotlin app's latest.json is never offered to it. */
const RUST_LATEST_KEY = "family-tv-rust-latest.json";
const LATEST_KEY = "latest.json";

const PAIR_TTL_MS = 30 * 60 * 1000;
const COMMAND_TYPES = new Set(["refresh", "diagnose", "clear_cache", "restart", "message", "update"]);
const MAX_BODY = 256 * 1024;

class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

async function body<T>(req: Request): Promise<T> {
  const len = Number(req.headers.get("content-length") ?? "0");
  if (len > MAX_BODY) throw new HttpError(413, "body too large");
  const text = await req.text();
  if (text.length > MAX_BODY) throw new HttpError(413, "body too large");
  try {
    return JSON.parse(text || "{}") as T;
  } catch {
    throw new HttpError(400, "invalid JSON");
  }
}

function bearer(req: Request): string {
  const h = req.headers.get("authorization") ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : "";
}

function requireAdmin(req: Request, env: Env) {
  const t = bearer(req);
  if (!env.ADMIN_TOKEN || !t || !timingSafeEqual(t, env.ADMIN_TOKEN)) throw new HttpError(401, "admin token required");
}

interface DeviceRow {
  id: string;
  pair_code: string | null;
  pair_expires: number | null;
  nickname: string | null;
  profile_id: string | null;
  manufacturer: string | null;
  model: string | null;
  sdk: number | null;
  app_version: string | null;
  country: string | null;
  last_seen: number | null;
  status_json: string | null;
  created_at: number;
}

async function requireDevice(req: Request, env: Env): Promise<DeviceRow> {
  const t = bearer(req);
  if (!t) throw new HttpError(401, "device token required");
  const row = await env.DB.prepare("SELECT * FROM devices WHERE token_hash = ?").bind(await sha256Hex(t)).first<DeviceRow>();
  if (!row) throw new HttpError(401, "unknown device");
  return row;
}

/** A TV's last status; null when missing or cut short (the column keeps 8,000 characters). */
function parseStatus(raw: string | null): unknown {
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function loadProfile(env: Env, id: string | null): Promise<ProfileData | null> {
  if (!id) return null;
  const row = await env.DB.prepare("SELECT data_enc FROM profiles WHERE id = ?").bind(id).first<{ data_enc: string }>();
  return row ? openJson<ProfileData>(env.CONFIG_KEY, row.data_enc) : null;
}

/** Hands queued commands to the device once; they stay listed until acked. */
async function takeCommands(env: Env, deviceId: string, now: number) {
  const { results } = await env.DB.prepare(
    "SELECT id, type, payload FROM commands WHERE device_id = ? AND acked_at IS NULL AND (delivered_at IS NULL OR delivered_at < ?) ORDER BY created_at LIMIT 20",
  )
    .bind(deviceId, now - 5 * 60 * 1000) // redeliver if not acked within 5 min (app restarted mid-command)
    .all<{ id: string; type: string; payload: string | null }>();
  if (results.length) {
    await env.DB.batch(results.map((c) => env.DB.prepare("UPDATE commands SET delivered_at = ? WHERE id = ?").bind(now, c.id)));
  }
  return results.map((c) => ({ id: c.id, type: c.type, payload: c.payload ? JSON.parse(c.payload) : null }));
}

// CI uploads latest.json next to the APK; cache it briefly so every check-in isn't an R2 read.
let latestCache: { at: number; bucket?: R2Bucket; value: { versionCode: number; versionName?: string } | null } | null = null;

export async function latestApp(env: Env, req: Request) {
  const now = Date.now();
  if (!latestCache || latestCache.bucket !== env.APPS || now - latestCache.at > 60_000) {
    const obj = env.APPS ? await env.APPS.get(LATEST_KEY) : null;
    const value = obj ? ((await obj.json().catch(() => null)) as { versionCode: number; versionName?: string } | null) : null;
    latestCache = { at: now, bucket: env.APPS, value };
  }
  const published = latestCache.value;
  if (published && Number(published.versionCode) > 0) {
    return { versionCode: Number(published.versionCode), versionName: published.versionName ?? null, url: new URL("/tv", req.url).toString() };
  }
  // Manual fallback: wrangler.toml vars.
  const code = Number(env.LATEST_APP_VERSION_CODE ?? "0");
  return code > 0 && env.LATEST_APP_URL ? { versionCode: code, versionName: null, url: env.LATEST_APP_URL } : null;
}

const optionalKey = (v: string | undefined): string | null => {
  const key = v?.trim();
  return key ? key : null;
};

/** The subtitle key for paired devices; null (online subtitles hidden on the TV) when the secret isn't set. */
export function subtitlesApiKey(env: Env): string | null {
  return optionalKey(env.OPENSUBTITLES_API_KEY);
}

/** The TMDB key for paired devices; null (no TMDB trailers or episode details) when the secret isn't set. */
export function tmdbApiKey(env: Env): string | null {
  return optionalKey(env.TMDB_API_KEY);
}

/** The TheTVDB key for paired devices; null (no TVDB fallback) when the secret isn't set. */
export function tvdbApiKey(env: Env): string | null {
  return optionalKey(env.TVDB_API_KEY);
}

async function freshPairCode(env: Env): Promise<string> {
  for (let i = 0; i < 5; i++) {
    const code = pairCode();
    const clash = await env.DB.prepare("SELECT 1 FROM devices WHERE pair_code = ?").bind(code).first();
    if (!clash) return code;
  }
  throw new HttpError(503, "could not allocate a pairing code");
}

// ---------- device API ----------

async function deviceRegister(req: Request, env: Env) {
  const b = await body<{ manufacturer?: string; model?: string; sdk?: number; appVersion?: string }>(req);
  const id = randomId(8);
  const token = randomId(32);
  const now = Date.now();
  const code = await freshPairCode(env);
  await env.DB.prepare(
    "INSERT INTO devices (id, token_hash, pair_code, pair_expires, manufacturer, model, sdk, app_version, country, last_seen, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
  )
    .bind(id, await sha256Hex(token), code, now + PAIR_TTL_MS, clip(b.manufacturer), clip(b.model), b.sdk ?? null, clip(b.appVersion), country(req), now, now)
    .run();
  return json({ deviceId: id, deviceToken: token, pairCode: code, pairExpires: now + PAIR_TTL_MS });
}

async function deviceConfig(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  const now = Date.now();
  if (!d.profile_id) {
    // Unpaired: keep a valid code on screen.
    let code = d.pair_code;
    let expires = d.pair_expires ?? 0;
    if (!code || expires < now) {
      code = await freshPairCode(env);
      expires = now + PAIR_TTL_MS;
      await env.DB.prepare("UPDATE devices SET pair_code = ?, pair_expires = ?, last_seen = ? WHERE id = ?").bind(code, expires, now, d.id).run();
    } else {
      await env.DB.prepare("UPDATE devices SET last_seen = ? WHERE id = ?").bind(now, d.id).run();
    }
    return json({ paired: false, pairCode: code, pairExpires: expires });
  }
  const profile = await loadProfile(env, d.profile_id);
  await env.DB.prepare("UPDATE devices SET last_seen = ?, country = ? WHERE id = ?").bind(now, country(req), d.id).run();
  return json({
    paired: true,
    nickname: d.nickname,
    profile: profile && forDevice(await withSorterLinks(env, profile)),
    commands: await takeCommands(env, d.id, now),
    latestApp: await latestApp(env, req),
    // The client id is public (it goes in every Trakt API call); the secret never leaves the Worker.
    trakt: traktConfigured(env) ? { clientId: env.TRAKT_CLIENT_ID } : null,
    subtitlesApiKey: subtitlesApiKey(env),
    tmdbApiKey: tmdbApiKey(env),
    tvdbApiKey: tvdbApiKey(env),
    realDebrid: await rdAccount(env, d.profile_id),
  });
}

async function deviceHeartbeat(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  const b = await body<{ appVersion?: string; status?: unknown }>(req);
  const now = Date.now();
  await env.DB.prepare("UPDATE devices SET last_seen = ?, app_version = COALESCE(?, app_version), status_json = ?, country = ? WHERE id = ?")
    .bind(now, clip(b.appVersion), JSON.stringify(normalizeGuideStatus(b.status)).slice(0, 8000), country(req), d.id)
    .run();
  return json({
    paired: !!d.profile_id,
    commands: d.profile_id ? await takeCommands(env, d.id, now) : [],
    latestApp: d.profile_id ? await latestApp(env, req) : null,
    // Another TV marked or paused something when this is past the seq this TV last pulled.
    progressSeq: d.profile_id ? await progressSeq(env, d.profile_id) : null,
  });
}

/** New telemetry counts have a numeric schema; the renderer independently handles old stored data. */
export function normalizeGuideStatus(status: unknown): unknown {
  if (!status || typeof status !== "object" || Array.isArray(status)) return status ?? null;
  const s = status as Record<string, unknown>;
  if (!s.guide || typeof s.guide !== "object" || Array.isArray(s.guide)) return { ...s, guide: null };
  const g = s.guide as Record<string, unknown>;
  const count = (v: unknown) => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? Math.min(v, 1_000_000_000) : 0;
  const linked = g.linked && typeof g.linked === "object" && !Array.isArray(g.linked) ? g.linked : {};
  return { ...s, guide: { ...g, switched: count(g.switched), thin: count(g.thin), unmatchedCount: count(g.unmatchedCount),
    linked: Object.fromEntries(Object.entries(linked).map(([k, v]) => [k, count(v)])) } };
}

async function deviceAck(req: Request, env: Env, commandId: string) {
  const d = await requireDevice(req, env);
  const b = await body<{ result?: unknown }>(req);
  await env.DB.prepare("UPDATE commands SET acked_at = ?, result = ? WHERE id = ? AND device_id = ?")
    .bind(Date.now(), JSON.stringify(b.result ?? null).slice(0, 8000), commandId, d.id)
    .run();
  return json({ ok: true });
}

async function deviceDiagnostics(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  const b = await body<{ verdict?: string; report?: unknown }>(req);
  const verdict = b.verdict && VERDICTS[b.verdict] ? b.verdict : "UNKNOWN";
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO diagnostics (id, device_id, created_at, verdict, report_json) VALUES (?,?,?,?,?)").bind(
      randomId(8),
      d.id,
      now,
      verdict,
      JSON.stringify(b.report ?? null).slice(0, 64000),
    ),
    // Keep the last 20 reports per device.
    env.DB.prepare(
      "DELETE FROM diagnostics WHERE device_id = ? AND id NOT IN (SELECT id FROM diagnostics WHERE device_id = ? ORDER BY created_at DESC LIMIT 20)",
    ).bind(d.id, d.id),
  ]);
  return json({ ok: true });
}

/** After loading its lists, a TV sends its category names so the admin page can arrange them. */
async function deviceCategories(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  if (!d.profile_id) throw new HttpError(409, "device not paired");
  const b = await body<Record<string, unknown>>(req);
  const now = Date.now();
  const stmts = CATEGORY_KINDS.map((k) => [k, cleanNames(b[k])] as const)
    .filter(([, names]) => names.length)
    .map(([k, names]) =>
      env.DB.prepare(
        `INSERT INTO profile_categories (profile_id, kind, names_json, updated_at) VALUES (?,?,?,?)
         ON CONFLICT (profile_id, kind) DO UPDATE SET names_json = excluded.names_json, updated_at = excluded.updated_at`,
      ).bind(d.profile_id, k, JSON.stringify(names), now),
    );
  if (stmts.length) await env.DB.batch(stmts);
  return json({ ok: true });
}

/** The TV asks "can *you* reach these?" so it can tell a local block from a dead server. */
async function deviceReachability(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  const b = await body<{ urls?: string[] }>(req);
  const profile = await loadProfile(env, d.profile_id);
  if (!profile) throw new HttpError(409, "device not paired");
  const allowed = profileHosts(profile);
  const urls = (b.urls ?? []).slice(0, 8).filter((u) => {
    try {
      return allowed.has(new URL(u).host.toLowerCase());
    } catch {
      return false;
    }
  });
  return json({ results: await Promise.all(urls.map((u) => probe(u))) });
}

// ---------- Trakt token proxy ----------
// Trakt's device-code login needs the app's client secret to exchange and refresh tokens. It
// stays here as a Worker secret, and only paired TVs may use it. Everything else the TV sends
// straight to api.trakt.tv with the user's own access token.

const TRAKT_API = "https://api.trakt.tv";
/**
 * Must equal the Redirect URI registered on the Trakt app (Trakt rejects the old out-of-band URI).
 * The device-code flow never redirects there; Trakt only checks it on refresh.
 */
export function traktRedirectUri(env: Env, req: Request): string {
  return env.TRAKT_REDIRECT_URI || new URL("/trakt", req.url).toString();
}

export function traktConfigured(env: Env): boolean {
  return !!env.TRAKT_CLIENT_ID;
}

/** Trakt apps made since Oct 2026 are PKCE-only and have no secret; older apps still send theirs. */
function traktClient(env: Env): Record<string, string> {
  return env.TRAKT_CLIENT_SECRET
    ? { client_id: env.TRAKT_CLIENT_ID!, client_secret: env.TRAKT_CLIENT_SECRET }
    : { client_id: env.TRAKT_CLIENT_ID! };
}

async function requireTraktDevice(req: Request, env: Env): Promise<DeviceRow> {
  const d = await requireDevice(req, env);
  if (!d.profile_id) throw new HttpError(403, "device not paired");
  if (!traktConfigured(env)) throw new HttpError(404, "trakt not configured");
  return d;
}

function tokenField(v: unknown, name: string): string {
  if (typeof v !== "string" || !v || v.length > 512) throw new HttpError(400, `${name} is required`);
  return v;
}

/**
 * Calls Trakt and wraps its answer as {status, data} with HTTP 200, so the TV can tell Trakt's
 * codes (400 = still waiting for the user, 410 = code expired, 418 = denied, 401 = bad refresh
 * token) apart from the portal's own 401 for an unknown device.
 */
async function traktPost(env: Env, path: string, payload: Record<string, string>) {
  let res: Response;
  try {
    res = await fetch(TRAKT_API + path, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "trakt-api-version": "2",
        "trakt-api-key": env.TRAKT_CLIENT_ID!,
        "user-agent": "FamilyTV-Portal/1",
      },
      body: JSON.stringify(payload),
    });
  } catch {
    throw new HttpError(502, "trakt unreachable");
  }
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  return json({ status: res.status, data });
}

async function traktDeviceCode(req: Request, env: Env) {
  await requireTraktDevice(req, env);
  return traktPost(env, "/oauth/device/code", { client_id: env.TRAKT_CLIENT_ID! });
}

async function traktDeviceToken(req: Request, env: Env) {
  await requireTraktDevice(req, env);
  const b = await body<{ code?: unknown }>(req);
  return traktPost(env, "/oauth/device/token", {
    code: tokenField(b.code, "code"),
    ...traktClient(env),
  });
}

async function traktRefresh(req: Request, env: Env) {
  await requireTraktDevice(req, env);
  const b = await body<{ refreshToken?: unknown }>(req);
  return traktPost(env, "/oauth/token", {
    refresh_token: tokenField(b.refreshToken, "refreshToken"),
    ...traktClient(env),
    redirect_uri: traktRedirectUri(env, req),
    grant_type: "refresh_token",
  });
}

async function traktRevoke(req: Request, env: Env) {
  await requireTraktDevice(req, env);
  const b = await body<{ token?: unknown }>(req);
  return traktPost(env, "/oauth/revoke", {
    token: tokenField(b.token, "token"),
    ...traktClient(env),
  });
}

// ---------- Real-Debrid, one account per profile ----------
// A TV signs in with Real-Debrid's device code itself, then hands the credentials here. Every TV
// in the profile then asks the portal for the access token, and only the portal refreshes it,
// so TVs never race each other's refresh.

const RD_TOKEN_URL = "https://api.real-debrid.com/oauth/v2/token";
const RD_GRANT = "http://oauth.net/grant_type/device/1.0";
const RD_REFRESH_MARGIN_MS = 10 * 60 * 1000;

interface RdSecret {
  clientId: string;
  clientSecret: string;
  accessToken: string;
  refreshToken: string;
}

interface RdRow {
  data_enc: string;
  username: string | null;
  expires_at: number;
}

async function requirePaired(req: Request, env: Env): Promise<string> {
  const d = await requireDevice(req, env);
  if (!d.profile_id) throw new HttpError(403, "device not paired");
  return d.profile_id;
}

/** Separate from OAuth: explicitly entered API keys shared only by the owner's current-profile devices. */
async function rdApiKey(req: Request, env: Env, url: URL): Promise<Response> {
  try {
    if (url.protocol !== "https:") throw new HttpError(426, "HTTPS required");
    const d = await requireDevice(req, env);
    const b = req.method === "GET" ? null : await body<Record<string, unknown>>(req);
    const profileId = req.method === "GET" ? url.searchParams.get("profileId") : b?.profileId;
    if (typeof profileId !== "string" || !profileId || profileId !== d.profile_id) throw new HttpError(403, "current profile required");
    const tokenHash = await sha256Hex(bearer(req));
    // Re-check membership and token in the same statement that reads the secret or changes it.
    const read = () => env.DB.prepare(
      `SELECT COALESCE(k.revision, 0) AS revision, k.key_enc FROM devices d
       LEFT JOIN profile_rd_api_keys k ON k.profile_id = d.profile_id
       WHERE d.id = ? AND d.token_hash = ? AND d.profile_id = ?`,
    ).bind(d.id, tokenHash, profileId).first<{ revision: number; key_enc: string | null }>();
    if (req.method === "GET") {
      const row = await read();
      if (!row) throw new HttpError(403, "current profile required");
      let apiKey: string | null = null;
      if (row.key_enc) {
        const opened = await openJson<{ profileId: string; apiKey: string }>(env.CONFIG_KEY, row.key_enc);
        if (opened.profileId !== profileId || !validRdApiKey(opened.apiKey)) throw new HttpError(500, "credential unavailable");
        apiKey = opened.apiKey;
      }
      // Decryption yields: reject if the key was removed/replaced or device moved meanwhile.
      const current = await read();
      if (!current) throw new HttpError(403, "current profile required");
      if (current.revision !== row.revision || current.key_enc !== row.key_enc) throw new HttpError(409, "credential changed; reload");
      return json({ profileId, revision: row.revision, configured: apiKey !== null, apiKey });
    }
    const revision = b?.revision;
    if (typeof revision !== "number" || !Number.isSafeInteger(revision) || revision < 0 || revision >= Number.MAX_SAFE_INTEGER) throw new HttpError(400, "valid revision required");
    const apiKey = req.method === "PUT" ? b?.apiKey : null;
    if (req.method === "PUT" && !validRdApiKey(apiKey)) throw new HttpError(400, "API key must contain 16 to 512 printable non-space ASCII characters");
    const sealed = apiKey === null ? null : await sealJson(env.CONFIG_KEY, { profileId, apiKey });
    const changed = await env.DB.prepare(
      `INSERT INTO profile_rd_api_keys (profile_id, revision, key_enc, updated_at)
       SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM devices WHERE id = ? AND token_hash = ? AND profile_id = ?)
         AND (? = 0 OR EXISTS (SELECT 1 FROM profile_rd_api_keys WHERE profile_id = ? AND revision = ?))
       ON CONFLICT (profile_id) DO UPDATE SET revision = excluded.revision, key_enc = excluded.key_enc, updated_at = excluded.updated_at
       WHERE profile_rd_api_keys.revision = ?
       RETURNING revision`,
    ).bind(profileId, revision + 1, sealed, Date.now(), d.id, tokenHash, profileId, revision, profileId, revision, revision)
      .first<{ revision: number }>();
    if (!changed) {
      if (!await read()) throw new HttpError(403, "current profile required");
      throw new HttpError(409, "credential changed; reload");
    }
    return json({ profileId, revision: changed.revision, configured: apiKey !== null });
  } catch (e) {
    // Never let crypto/DB exceptions reach routine logs with credential-bearing context.
    if (e instanceof HttpError) throw e;
    throw new HttpError(500, "credential unavailable");
  }
}

/** Admin can replace/remove a profile credential, and read only a server-masked preview. */
async function adminRdApiKey(req: Request, env: Env, url: URL, profileId: string): Promise<Response> {
  try {
    if (url.protocol !== "https:") throw new HttpError(426, "HTTPS required");
    const read = () => env.DB.prepare(
      `SELECT COALESCE(k.revision, 0) AS revision, k.key_enc
       FROM profiles p LEFT JOIN profile_rd_api_keys k ON k.profile_id = p.id WHERE p.id = ?`,
    ).bind(profileId).first<{ revision: number; key_enc: string | null }>();
    const row = await read();
    if (!row) throw new HttpError(404, "profile not found");
    if (req.method === "GET") {
      let maskedPreview: string | null = null;
      if (row.key_enc !== null) {
        const opened = await openJson<{ profileId: string; apiKey: string } | null>(env.CONFIG_KEY, row.key_enc);
        if (!opened || opened.profileId !== profileId || !validRdApiKey(opened.apiKey)) throw new HttpError(500, "credential unavailable");
        maskedPreview = maskRdApiKey(opened.apiKey);
      }
      // Decryption yields: never return a removed/replaced key or a deleted profile's preview.
      const current = await read();
      if (!current) throw new HttpError(404, "profile not found");
      if (current.revision !== row.revision || current.key_enc !== row.key_enc) throw new HttpError(409, "credential changed; reload");
      return json({ profileId, revision: row.revision, configured: maskedPreview !== null, maskedPreview });
    }
    const b = await body<Record<string, unknown>>(req);
    const revision = b.revision;
    if (typeof revision !== "number" || !Number.isSafeInteger(revision) || revision < 0 || revision >= Number.MAX_SAFE_INTEGER) throw new HttpError(400, "valid revision required");
    const apiKey = req.method === "PUT" ? b.apiKey : null;
    if (req.method === "PUT" && !validRdApiKey(apiKey)) throw new HttpError(400, "API key must contain 16 to 512 printable non-space ASCII characters");
    const sealed = apiKey === null ? null : await sealJson(env.CONFIG_KEY, { profileId, apiKey });
    const changed = await env.DB.prepare(
      `INSERT INTO profile_rd_api_keys (profile_id, revision, key_enc, updated_at)
       SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM profiles WHERE id = ?)
         AND (? = 0 OR EXISTS (SELECT 1 FROM profile_rd_api_keys WHERE profile_id = ? AND revision = ?))
       ON CONFLICT (profile_id) DO UPDATE SET revision = excluded.revision, key_enc = excluded.key_enc, updated_at = excluded.updated_at
       WHERE profile_rd_api_keys.revision = ? RETURNING revision`,
    ).bind(profileId, revision + 1, sealed, Date.now(), profileId, revision, profileId, revision, revision).first<{ revision: number }>();
    if (!changed) {
      if (!await read()) throw new HttpError(404, "profile not found");
      throw new HttpError(409, "credential changed; reload");
    }
    return json({ profileId, revision: changed.revision, configured: apiKey !== null, maskedPreview: apiKey === null ? null : maskRdApiKey(apiKey) });
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(500, "credential unavailable");
  }
}

function validRdApiKey(v: unknown): v is string {
  return typeof v === "string" && /^[\x21-\x7e]{16,512}$/.test(v);
}

function maskRdApiKey(key: unknown): string {
  if (!validRdApiKey(key)) throw new HttpError(500, "credential unavailable");
  return key.slice(0, 4) + "********" + key.slice(-4);
}

const rdRow = (env: Env, profileId: string) =>
  env.DB.prepare("SELECT data_enc, username, expires_at FROM profile_debrid WHERE profile_id = ?").bind(profileId).first<RdRow>();

/** For the device config: whose Real-Debrid account the profile uses, or null. */
async function rdAccount(env: Env, profileId: string): Promise<{ username: string | null } | null> {
  // Before the migration runs the table is missing; that must not break every TV's config.
  const row = await rdRow(env, profileId).catch(() => null);
  return row ? { username: row.username } : null;
}

async function rdSave(env: Env, profileId: string, s: RdSecret, username: string | null, expiresAt: number, by: string | null) {
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO profile_debrid (profile_id, data_enc, username, expires_at, linked_by, updated_at) VALUES (?,?,?,?,?,?)
     ON CONFLICT (profile_id) DO UPDATE SET data_enc = excluded.data_enc, username = excluded.username,
       expires_at = excluded.expires_at, linked_by = COALESCE(excluded.linked_by, linked_by), updated_at = excluded.updated_at`,
  )
    .bind(profileId, await sealJson(env.CONFIG_KEY, s), username, expiresAt, by, now)
    .run();
}

async function rdLink(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  if (!d.profile_id) throw new HttpError(403, "device not paired");
  const b = await body<Record<string, unknown>>(req);
  const secret: RdSecret = {
    clientId: tokenField(b.clientId, "clientId"),
    clientSecret: tokenField(b.clientSecret, "clientSecret"),
    accessToken: tokenField(b.accessToken, "accessToken"),
    refreshToken: tokenField(b.refreshToken, "refreshToken"),
  };
  const expiresIn = Number(b.expiresIn) > 0 ? Number(b.expiresIn) : 3600;
  await rdSave(env, d.profile_id, secret, clip(typeof b.username === "string" ? b.username : null), Date.now() + expiresIn * 1000, d.id);
  await refreshProfileTvs(env, d.profile_id);
  return json({ ok: true });
}

/**
 * The profile's access token, refreshed when it is close to running out, or when a TV says
 * Real-Debrid refused it ({rejected: token}). 404: no account; 410: the account must sign in again.
 */
async function rdToken(req: Request, env: Env) {
  const profileId = await requirePaired(req, env);
  const b = req.method === "POST" ? await body<{ rejected?: unknown }>(req) : {};
  const row = await rdRow(env, profileId);
  if (!row) return json({ error: "not linked" }, 404);
  const s = await openJson<RdSecret>(env.CONFIG_KEY, row.data_enc);
  const current = { accessToken: s.accessToken, expiresAt: row.expires_at, username: row.username };
  const rejected = b.rejected === s.accessToken;
  if (!rejected && row.expires_at - Date.now() > RD_REFRESH_MARGIN_MS) return json(current);
  let res: Response;
  try {
    res = await fetch(RD_TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", "user-agent": "FamilyTV-Portal/1" },
      body: new URLSearchParams({ client_id: s.clientId, client_secret: s.clientSecret, code: s.refreshToken, grant_type: RD_GRANT }).toString(),
    });
  } catch {
    throw new HttpError(502, "real-debrid unreachable");
  }
  if ([400, 401, 403].includes(res.status)) {
    await env.DB.prepare("DELETE FROM profile_debrid WHERE profile_id = ?").bind(profileId).run();
    await refreshProfileTvs(env, profileId);
    return json({ error: "signed out" }, 410);
  }
  const t = res.ok ? ((await res.json().catch(() => null)) as { access_token?: string; refresh_token?: string; expires_in?: number } | null) : null;
  if (!t?.access_token) {
    // Real-Debrid is having a moment: an unexpired token may still work.
    if (!rejected && row.expires_at > Date.now()) return json(current);
    throw new HttpError(502, `real-debrid refresh failed (${res.status})`);
  }
  const next: RdSecret = { ...s, accessToken: t.access_token, refreshToken: t.refresh_token || s.refreshToken };
  const expiresAt = Date.now() + (Number(t.expires_in) > 0 ? Number(t.expires_in) : 3600) * 1000;
  await rdSave(env, profileId, next, row.username, expiresAt, null);
  return json({ accessToken: next.accessToken, expiresAt, username: row.username });
}

/** Signs the whole profile out of Real-Debrid, from a TV or the admin page. */
async function rdDisconnect(env: Env, profileId: string) {
  await env.DB.prepare("DELETE FROM profile_debrid WHERE profile_id = ?").bind(profileId).run();
  await refreshProfileTvs(env, profileId);
  return json({ ok: true });
}

// ---------- watched marks and resume points, shared by a profile's TVs ----------
// Each TV pushes the rows it changed and pulls what changed after the last seq it saw. A row only
// replaces the stored one when it was changed later, so the newest mark or resume spot wins.

const PROGRESS_PUSH_MAX = 500;
const PROGRESS_PAGE = 1000;
/** A TV clock running fast can't make its rows win for long. */
const PROGRESS_CLOCK_SLACK_MS = 5 * 60 * 1000;

interface ProgressRow {
  key: string;
  kind: string;
  sourceId: string;
  itemId: string;
  season: number;
  episode: number;
  positionMs: number;
  durationMs: number;
  percent: number;
  watched: boolean;
  updatedAt: number;
}

const wholeNumber = (v: unknown) => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0);
const shortText = (v: unknown) => (typeof v === "string" && v.length > 0 && v.length <= 120 ? v : null);

/** A row a TV sent, or null when it isn't one (skipped rather than failing the whole push). */
export function progressRow(v: unknown, now: number): ProgressRow | null {
  const r = (v ?? {}) as Record<string, unknown>;
  const key = typeof r.key === "string" && r.key.length <= 300 && /^[me]\//.test(r.key) ? r.key : null;
  const kind = r.kind === "movie" || r.kind === "episode" ? r.kind : null;
  const sourceId = shortText(r.sourceId);
  const itemId = shortText(r.itemId);
  const updatedAt = wholeNumber(r.updatedAt);
  if (!key || !kind || !sourceId || !itemId || !updatedAt) return null;
  const percent = typeof r.percent === "number" && Number.isFinite(r.percent) ? Math.min(100, Math.max(0, r.percent)) : 0;
  return {
    key, kind, sourceId, itemId,
    season: wholeNumber(r.season),
    episode: wholeNumber(r.episode),
    positionMs: wholeNumber(r.positionMs),
    durationMs: wholeNumber(r.durationMs),
    percent,
    watched: r.watched === true,
    updatedAt: Math.min(updatedAt, now + PROGRESS_CLOCK_SLACK_MS),
  };
}

/** The profile's latest change number, for heartbeats; null before the migration runs. */
async function progressSeq(env: Env, profileId: string): Promise<number | null> {
  const row = await env.DB.prepare("SELECT MAX(seq) AS seq FROM profile_progress WHERE profile_id = ?")
    .bind(profileId)
    .first<{ seq: number | null }>()
    .catch(() => undefined);
  return row === undefined ? null : row?.seq ?? 0;
}

async function progressPush(req: Request, env: Env) {
  const profileId = await requirePaired(req, env);
  const b = await body<{ rows?: unknown }>(req);
  if (!Array.isArray(b.rows) || b.rows.length > PROGRESS_PUSH_MAX) throw new HttpError(400, `rows: a list of up to ${PROGRESS_PUSH_MAX}`);
  const now = Date.now();
  const rows = b.rows.map((r) => progressRow(r, now)).filter((r): r is ProgressRow => r !== null);
  // Each upsert takes the next seq when it lands; D1 runs a batch in order, so seqs only grow.
  const stmts = rows.map((r) =>
    env.DB.prepare(
      `INSERT INTO profile_progress (profile_id, key, kind, source_id, item_id, season, episode, position_ms, duration_ms, percent, watched, updated_at, seq)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?, (SELECT COALESCE(MAX(seq), 0) + 1 FROM profile_progress WHERE profile_id = ?))
       ON CONFLICT (profile_id, key) DO UPDATE SET kind = excluded.kind, source_id = excluded.source_id, item_id = excluded.item_id,
         season = excluded.season, episode = excluded.episode, position_ms = excluded.position_ms, duration_ms = excluded.duration_ms,
         percent = excluded.percent, watched = excluded.watched, updated_at = excluded.updated_at, seq = excluded.seq
       WHERE excluded.updated_at > profile_progress.updated_at`,
    ).bind(profileId, r.key, r.kind, r.sourceId, r.itemId, r.season, r.episode, r.positionMs, r.durationMs, r.percent, r.watched ? 1 : 0, r.updatedAt, profileId),
  );
  if (stmts.length) await env.DB.batch(stmts);
  return json({ ok: true, saved: rows.length, seq: await progressSeq(env, profileId) });
}

/** Rows changed after ?since=<seq>, oldest first; `profile` lets a TV notice it was moved to another profile. */
async function progressPull(req: Request, env: Env, url: URL) {
  const profileId = await requirePaired(req, env);
  const since = wholeNumber(Number(url.searchParams.get("since") ?? 0));
  const { results } = await env.DB.prepare(
    `SELECT key, kind, source_id, item_id, season, episode, position_ms, duration_ms, percent, watched, updated_at, seq
     FROM profile_progress WHERE profile_id = ? AND seq > ? ORDER BY seq LIMIT ?`,
  )
    .bind(profileId, since, PROGRESS_PAGE)
    .all<{
      key: string; kind: string; source_id: string; item_id: string; season: number; episode: number;
      position_ms: number; duration_ms: number; percent: number; watched: number; updated_at: number; seq: number;
    }>();
  const rows: ProgressRow[] = results.map((r) => ({
    key: r.key, kind: r.kind, sourceId: r.source_id, itemId: r.item_id, season: r.season, episode: r.episode,
    positionMs: r.position_ms, durationMs: r.duration_ms, percent: r.percent, watched: r.watched === 1, updatedAt: r.updated_at,
  }));
  return json({ profile: profileId, rows, seq: results.length ? results[results.length - 1].seq : since, more: results.length === PROGRESS_PAGE });
}

/**
 * Anonymous usage stats from a TV, kept only for profiles with Usage stats on (`on: false` tells the
 * TV to stop queueing). Rows past 30 days go as new ones arrive.
 */
async function usagePush(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  if (!d.profile_id) throw new HttpError(403, "device not paired");
  const b = await body<{ app?: unknown; appVersion?: unknown; events?: unknown }>(req);
  if (!Array.isArray(b.events) || b.events.length > USAGE_BATCH_MAX) throw new HttpError(400, `events: a list of up to ${USAGE_BATCH_MAX}`);
  if (typeof b.app !== "string" || !(USAGE_APPS as readonly string[]).includes(b.app)) throw new HttpError(400, `app: one of ${USAGE_APPS.join(", ")}`);
  if (!(await loadProfile(env, d.profile_id))?.usageStats) return json({ ok: true, on: false, saved: 0 });
  const now = Date.now();
  const version = clip(typeof b.appVersion === "string" ? b.appVersion : null, 40);
  const events = b.events.map((e) => usageEvent(e, now)).filter((e) => e !== null);
  const stmts = events.map((e) =>
    env.DB.prepare("INSERT INTO usage_events (device_id, profile_id, app, app_version, at, screen, action, value) VALUES (?,?,?,?,?,?,?,?)")
      .bind(d.id, d.profile_id, b.app, version, e.at, e.screen, e.action, e.value),
  );
  stmts.push(env.DB.prepare("DELETE FROM usage_events WHERE at < ?").bind(now - USAGE_KEEP_MS));
  await env.DB.batch(stmts);
  return json({ ok: true, on: true, saved: events.length });
}

// ---------- admin API ----------

async function adminDevices(env: Env) {
  const { results } = await env.DB.prepare(
    `SELECT d.id, d.nickname, d.profile_id, p.name AS profile_name, d.manufacturer, d.model, d.sdk, d.app_version, d.country,
            d.last_seen, d.status_json, d.created_at,
            (SELECT verdict FROM diagnostics x WHERE x.device_id = d.id ORDER BY created_at DESC LIMIT 1) AS last_verdict,
            (SELECT created_at FROM diagnostics x WHERE x.device_id = d.id ORDER BY created_at DESC LIMIT 1) AS last_verdict_at
       FROM devices d LEFT JOIN profiles p ON p.id = d.profile_id
      WHERE d.profile_id IS NOT NULL
      ORDER BY d.nickname`,
  ).all();
  return json({ devices: results.map((r) => ({ ...r, status: parseStatus(r.status_json as string | null), status_json: undefined })) });
}

async function adminPair(req: Request, env: Env) {
  const b = await body<{ code?: string; profileId?: string; nickname?: string }>(req);
  const code = (b.code ?? "").trim().toUpperCase();
  if (!code || !b.profileId || !b.nickname?.trim()) throw new HttpError(400, "code, profileId and nickname are required");
  const profile = await env.DB.prepare("SELECT id FROM profiles WHERE id = ?").bind(b.profileId).first();
  if (!profile) throw new HttpError(404, "profile not found");
  const dev = await env.DB.prepare("SELECT id, pair_expires FROM devices WHERE pair_code = ?").bind(code).first<{ id: string; pair_expires: number }>();
  if (!dev || dev.pair_expires < Date.now()) throw new HttpError(404, "code not found or expired; check the TV screen");
  await env.DB.prepare("UPDATE devices SET profile_id = ?, nickname = ?, pair_code = NULL, pair_expires = NULL WHERE id = ?")
    .bind(b.profileId, b.nickname.trim().slice(0, 60), dev.id)
    .run();
  return json({ deviceId: dev.id });
}

async function adminUpdateDevice(req: Request, env: Env, id: string) {
  const b = await body<{ nickname?: string; profileId?: string }>(req);
  if (b.profileId) {
    const p = await env.DB.prepare("SELECT id FROM profiles WHERE id = ?").bind(b.profileId).first();
    if (!p) throw new HttpError(404, "profile not found");
  }
  await env.DB.prepare("UPDATE devices SET nickname = COALESCE(?, nickname), profile_id = COALESCE(?, profile_id) WHERE id = ?")
    .bind(b.nickname?.trim().slice(0, 60) ?? null, b.profileId ?? null, id)
    .run();
  if (b.profileId) await queueCommand(env, id, "refresh", null);
  return json({ ok: true });
}

async function queueCommand(env: Env, deviceId: string, type: string, payload: unknown) {
  const id = randomId(8);
  await env.DB.prepare("INSERT INTO commands (id, device_id, type, payload, created_at) VALUES (?,?,?,?,?)")
    .bind(id, deviceId, type, payload == null ? null : JSON.stringify(payload), Date.now())
    .run();
  return id;
}

async function adminCommand(req: Request, env: Env, deviceId: string) {
  const b = await body<{ type?: string; payload?: unknown }>(req);
  if (!b.type || !COMMAND_TYPES.has(b.type)) throw new HttpError(400, `type must be one of ${[...COMMAND_TYPES].join(", ")}`);
  const dev = await env.DB.prepare("SELECT id FROM devices WHERE id = ?").bind(deviceId).first();
  if (!dev) throw new HttpError(404, "device not found");
  return json({ commandId: await queueCommand(env, deviceId, b.type, b.payload ?? null) });
}

async function adminDeviceDetail(env: Env, id: string) {
  const dev = await env.DB.prepare("SELECT profile_id FROM devices WHERE id = ?").bind(id).first<{ profile_id: string | null }>();
  const [diag, cmds] = await Promise.all([
    env.DB.prepare("SELECT id, created_at, verdict, report_json FROM diagnostics WHERE device_id = ? ORDER BY created_at DESC LIMIT 10").bind(id).all(),
    env.DB.prepare("SELECT id, type, created_at, delivered_at, acked_at, result FROM commands WHERE device_id = ? ORDER BY created_at DESC LIMIT 20").bind(id).all(),
  ]);
  return json({
    diagnostics: diag.results.map((r) => ({
      ...r,
      report: JSON.parse(r.report_json as string),
      report_json: undefined,
      ...(VERDICTS[r.verdict as string] ?? { label: r.verdict, advice: "" }),
    })),
    commands: cmds.results,
    // Suggestions from the latest check, against the profile as it is now.
    suggestions: diag.results.length ? suggestions(JSON.parse(diag.results[0].report_json as string) ?? {}, await loadProfile(env, dev?.profile_id ?? null)) : [],
  });
}

/** One-click fix from a suggestion: try this server first for the line. */
async function adminPreferServer(req: Request, env: Env, id: string) {
  const b = await body<{ sourceId?: unknown; url?: unknown }>(req);
  const profile = await loadProfile(env, id);
  if (!profile) throw new HttpError(404, "profile not found");
  try {
    preferServer(profile, String(b.sourceId), String(b.url));
  } catch (e) {
    throw new HttpError(400, (e as Error).message);
  }
  await env.DB.prepare("UPDATE profiles SET data_enc = ?, updated_at = ? WHERE id = ?").bind(await sealJson(env.CONFIG_KEY, profile), Date.now(), id).run();
  await refreshProfileTvs(env, id);
  return json({ ok: true });
}

/** Tells every TV on the profile to fetch its config now. */
async function refreshProfileTvs(env: Env, profileId: string) {
  const { results } = await env.DB.prepare("SELECT id FROM devices WHERE profile_id = ?").bind(profileId).all<{ id: string }>();
  for (const d of results) await queueCommand(env, d.id, "refresh", null);
}

async function adminProfiles(env: Env) {
  const { results } = await env.DB.prepare("SELECT id, name, data_enc, updated_at FROM profiles ORDER BY name").all<{
    id: string;
    name: string;
    data_enc: string;
    updated_at: number;
  }>();
  const out = await Promise.all(
    results.map(async (r) => ({
      id: r.id,
      name: r.name,
      updated_at: r.updated_at,
      data: maskSecrets(await openJson<ProfileData>(env.CONFIG_KEY, r.data_enc)),
      realDebrid: await rdAccount(env, r.id),
    })),
  );
  return json({ profiles: out });
}

async function adminProfileCategories(env: Env, id: string) {
  const { results } = await env.DB.prepare("SELECT kind, names_json, updated_at FROM profile_categories WHERE profile_id = ?")
    .bind(id)
    .all<{ kind: string; names_json: string; updated_at: number }>();
  const out: Record<string, { names: string[]; updatedAt: number }> = {};
  for (const r of results) out[r.kind] = { names: JSON.parse(r.names_json), updatedAt: r.updated_at };
  return json({ reported: out });
}

async function adminSaveProfile(req: Request, env: Env, id: string | null) {
  const b = await body<{ name?: string; data?: unknown }>(req);
  if (!b.name?.trim()) throw new HttpError(400, "name is required");
  let data: ProfileData;
  try {
    data = validateProfile(b.data);
  } catch (e) {
    throw new HttpError(400, (e as Error).message);
  }
  const now = Date.now();
  // Lines picked from the portal's list on the page: their passwords come from that list.
  const passwords = new Map<string, string>();
  const copies = data.sources.filter((x) => x.copyOf);
  // A line that so far only lives in another profile joins the list first.
  if (copies.length) await rememberLines(env, (await allProfiles(env)).flatMap((pr) => pr.data.sources), false);
  for (const s of copies) {
    const line = await lineByUsername(env, s.copyOf!);
    if (!line) throw new HttpError(409, `${s.name}: that line is no longer on the Lines page; pick it again`);
    passwords.set(line.username, line.password);
  }
  const passwordOf = (username: string) => passwords.get(username);
  if (id) {
    const existing = await loadProfile(env, id);
    if (!existing) throw new HttpError(404, "profile not found");
    data = mergeSecrets(data, existing, passwordOf);
    await env.DB.prepare("UPDATE profiles SET name = ?, data_enc = ?, updated_at = ? WHERE id = ?")
      .bind(b.name.trim(), await sealJson(env.CONFIG_KEY, data), now, id)
      .run();
    // Tell every TV on this profile to reload.
    const { results } = await env.DB.prepare("SELECT id FROM devices WHERE profile_id = ?").bind(id).all<{ id: string }>();
    for (const d of results) await queueCommand(env, d.id, "refresh", null);
  } else {
    id = randomId(8);
    data = mergeSecrets(data, null, passwordOf);
    await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES (?,?,?,?,?)")
      .bind(id, b.name.trim(), await sealJson(env.CONFIG_KEY, data), now, now)
      .run();
  }
  await rememberLines(env, data.sources, true);
  return json({ id });
}

// ---------- 8K reseller panel ----------

/** Re-ask the panel about a line after this long; the Lines page can force it. */
const LINE_STALE_MS = 6 * 60 * 60 * 1000;

function panel(env: Env): Panel {
  if (env.PANEL) return env.PANEL;
  const key = optionalKey(env.IPTV_PANEL_API_KEY);
  if (!key) throw new HttpError(404, "the 8K panel isn't set up: add the IPTV_PANEL_API_KEY secret to the portal");
  return new Panel(key);
}

const panelConfigured = (env: Env) => !!env.PANEL || !!optionalKey(env.IPTV_PANEL_API_KEY);

/** Panel failures are the panel's words, shown to the admin as a 502. */
async function viaPanel<T>(f: () => Promise<T>): Promise<T> {
  try {
    return await f();
  } catch (e) {
    if (e instanceof PanelError) throw new HttpError(502, e.message);
    throw e;
  }
}

async function adminPanel(env: Env) {
  if (!panelConfigured(env)) return json({ configured: false });
  const p = panel(env);
  try {
    const [reseller, packages] = await Promise.all([p.reseller(), p.packages()]);
    return json({ configured: true, reseller, packages, months: RENEW_MONTHS });
  } catch (e) {
    if (e instanceof PanelError) return json({ configured: true, error: e.message, months: RENEW_MONTHS });
    throw e;
  }
}

/** One of the portal's lines, as stored: the login is encrypted, the panel's answers are not. */
interface LineRow {
  username: string;
  data_enc: string;
  expire: string | null;
  enabled: number | null;
  max_connections: number | null;
  note: string | null;
  error: string | null;
  checked_at: number | null;
}

/** The encrypted part of a line: what a profile needs to use it. */
interface LineLogin {
  name: string;
  serverUrls: string[];
  password: string;
  /** The provider's page where this line's subscriber picks and orders channels (set on the Lines page). */
  sorterUrl?: string;
}

type Line = Omit<LineRow, "data_enc"> & LineLogin;

const MAX_ADD_LINES = 50;

async function allProfiles(env: Env) {
  const { results } = await env.DB.prepare("SELECT id, name, data_enc FROM profiles ORDER BY name").all<{ id: string; name: string; data_enc: string }>();
  return Promise.all(results.map(async (r) => ({ id: r.id, name: r.name, data: await openJson<ProfileData>(env.CONFIG_KEY, r.data_enc) })));
}

async function allLines(env: Env): Promise<Line[]> {
  const { results } = await env.DB.prepare("SELECT * FROM lines").all<LineRow>();
  return Promise.all(results.map(async ({ data_enc, ...r }) => ({ ...r, ...(await openJson<LineLogin>(env.CONFIG_KEY, data_enc)) })));
}

async function lineByUsername(env: Env, username: string): Promise<Line | null> {
  const r = await env.DB.prepare("SELECT * FROM lines WHERE username = ?").bind(username).first<LineRow>();
  if (!r) return null;
  const { data_enc, ...rest } = r;
  return { ...rest, ...(await openJson<LineLogin>(env.CONFIG_KEY, data_enc)) };
}

/** Adds a line, or updates its login (a changed login is checked again). The name only fills a blank. */
async function saveLine(env: Env, username: string, login: LineLogin) {
  const old = await lineByUsername(env, username);
  if (!old) {
    await env.DB.prepare("INSERT INTO lines (username, data_enc, created_at) VALUES (?,?,?)").bind(username, await sealJson(env.CONFIG_KEY, login), Date.now()).run();
    return;
  }
  const next: LineLogin = { name: old.name || login.name, serverUrls: login.serverUrls.length ? login.serverUrls : old.serverUrls, password: login.password, sorterUrl: old.sorterUrl };
  if (next.password === old.password && next.serverUrls.join() === old.serverUrls.join() && next.name === old.name) return;
  const recheck = next.password !== old.password;
  await env.DB.prepare(`UPDATE lines SET data_enc = ?${recheck ? ", checked_at = NULL" : ""} WHERE username = ?`)
    .bind(await sealJson(env.CONFIG_KEY, next), username)
    .run();
}

const xtreamLogins = (sources: Source[]) => sources.filter((s) => s.type === "xtream" && s.username && s.password && s.serverUrls?.length);

/**
 * Makes sure the portal's line list has every Xtream login in [sources]. With [update], a login
 * that changed in a profile updates the line too (a profile save); otherwise only new ones are added.
 */
async function rememberLines(env: Env, sources: Source[], update: boolean) {
  const known = update ? new Set<string>() : new Set((await env.DB.prepare("SELECT username FROM lines").all<{ username: string }>()).results.map((r) => r.username));
  for (const s of xtreamLogins(sources)) {
    if (known.has(s.username!)) continue;
    known.add(s.username!);
    await saveLine(env, s.username!, { name: s.name, serverUrls: s.serverUrls!, password: s.password! });
  }
}

async function checkLine(env: Env, p: Panel, username: string, password: string) {
  const now = Date.now();
  try {
    const l = await p.line(username, password);
    await env.DB.prepare("UPDATE lines SET expire = ?, enabled = ?, max_connections = ?, note = ?, error = NULL, checked_at = ? WHERE username = ?")
      .bind(l.expire, l.enabled ? 1 : 0, l.maxConnections, l.note, now, username)
      .run();
    return null;
  } catch (e) {
    if (!(e instanceof PanelError)) throw e;
    // Keep the last good expiry on screen; just note why this check failed.
    await env.DB.prepare("UPDATE lines SET error = ?, checked_at = ? WHERE username = ?").bind(e.message, now, username).run();
    return e.message;
  }
}

/**
 * Every line the portal knows (each profile's Xtream logins, plus lines added on the Lines page),
 * with what the panel last said about it and the profiles that use it. Lines not checked for
 * [LINE_STALE_MS] (or all, with [force]) are re-checked one at a time, gently.
 */
async function adminLines(env: Env, force: boolean) {
  const profiles = await allProfiles(env);
  await rememberLines(env, profiles.flatMap((pr) => pr.data.sources), false);
  let lines = await allLines(env);
  if (panelConfigured(env)) {
    const p = panel(env);
    const now = Date.now();
    const stale = lines.filter((l) => force || l.checked_at == null || now - l.checked_at > LINE_STALE_MS);
    for (const l of stale) await checkLine(env, p, l.username, l.password);
    if (stale.length) lines = await allLines(env);
  }
  const usedBy = new Map<string, { id: string; name: string }[]>();
  // A sorter link typed on a profile before the Lines page had one; shown so saving it there moves it over.
  const profileSorter = new Map<string, string>();
  for (const pr of profiles) {
    for (const s of xtreamLogins(pr.data.sources)) {
      if (s.sorterUrl && !profileSorter.has(s.username!)) profileSorter.set(s.username!, s.sorterUrl);
      const list = usedBy.get(s.username!) ?? [];
      if (!list.some((x) => x.id === pr.id)) list.push({ id: pr.id, name: pr.name });
      usedBy.set(s.username!, list);
    }
  }
  const out = lines.map((l) => ({
    username: l.username,
    name: l.name,
    serverUrls: l.serverUrls,
    sorterUrl: l.sorterUrl ?? profileSorter.get(l.username) ?? null,
    profiles: usedBy.get(l.username) ?? [],
    expire: l.expire,
    daysLeft: l.expire ? daysLeft(l.expire) : null,
    enabled: l.enabled == null ? null : l.enabled === 1,
    maxConnections: l.max_connections,
    note: l.note,
    error: l.error,
    checkedAt: l.checked_at,
  }));
  out.sort((a, b) => (a.daysLeft ?? 1e9) - (b.daysLeft ?? 1e9) || a.name.localeCompare(b.name));
  return json({ configured: panelConfigured(env), lines: out });
}

async function findLine(env: Env, username: unknown) {
  if (typeof username !== "string" || !username) throw new HttpError(400, "username is required");
  const line = await lineByUsername(env, username);
  if (!line) throw new HttpError(404, "line not found");
  return line;
}

/** Spends credits: the admin page confirms first. */
async function adminRenewLine(req: Request, env: Env) {
  const b = await body<{ username?: unknown; months?: unknown }>(req);
  const months = Number(b.months);
  if (!RENEW_MONTHS.includes(months)) throw new HttpError(400, `months must be one of ${RENEW_MONTHS.join(", ")}`);
  const line = await findLine(env, b.username);
  const p = panel(env);
  await viaPanel(() => p.renew(line.username, line.password, months));
  await checkLine(env, p, line.username, line.password);
  return json({ ok: true });
}

/** The server most of the portal's lines use, for a line added without one. */
function usualServer(lines: Line[]): string[] {
  const count = new Map<string, number>();
  for (const l of lines) if (l.serverUrls[0]) count.set(l.serverUrls[0], (count.get(l.serverUrls[0]) ?? 0) + 1);
  const best = [...count].sort((a, b) => b[1] - a[1])[0];
  return best ? [best[0]] : [];
}

/**
 * Adds lines that already exist on the panel to the portal's list, from their playlist links or
 * logins. Each is looked up on the panel first (read-only), so a typo isn't saved as a line.
 */
async function adminAddLines(req: Request, env: Env) {
  const b = await body<{ lines?: unknown }>(req);
  if (!Array.isArray(b.lines) || !b.lines.length) throw new HttpError(400, "lines is required");
  if (b.lines.length > MAX_ADD_LINES) throw new HttpError(400, `at most ${MAX_ADD_LINES} lines at a time`);
  const p = panel(env);
  // The fallback server comes from the lines profiles use, even before the Lines page was opened.
  await rememberLines(env, (await allProfiles(env)).flatMap((pr) => pr.data.sources), false);
  const fallback = usualServer(await allLines(env));
  const results: { username: string | null; ok: boolean; error?: string; expire?: string }[] = [];
  for (const raw of b.lines as Record<string, unknown>[]) {
    const text = (k: string) => (typeof raw?.[k] === "string" ? (raw[k] as string).trim() : "");
    const fromLink = text("link") ? loginFromLineUrl(text("link")) : null;
    if (text("link") && !fromLink) {
      results.push({ username: null, ok: false, error: "not a playlist link with a username and password" });
      continue;
    }
    const username = fromLink?.username ?? text("username");
    const password = fromLink?.password ?? text("password");
    const serverUrls = fromLink ? [fromLink.serverUrl] : text("serverUrl") ? [text("serverUrl").replace(/\/+$/, "")] : fallback;
    if (!username || !password) {
      results.push({ username: username || null, ok: false, error: "username and password are required" });
      continue;
    }
    if (!serverUrls.length || !/^https?:\/\//.test(serverUrls[0])) {
      results.push({ username, ok: false, error: "needs the line's server address" });
      continue;
    }
    let expire: string;
    try {
      expire = (await p.line(username, password)).expire;
    } catch (e) {
      if (!(e instanceof PanelError)) throw e;
      results.push({ username, ok: false, error: e.message });
      continue;
    }
    await saveLine(env, username, { name: text("name").slice(0, 60) || username, serverUrls, password });
    await checkLine(env, p, username, password);
    results.push({ username, ok: true, expire });
  }
  return json({ results });
}

/** Sets (or, when blank, clears) a line's package sorter link. */
async function adminLineSorter(req: Request, env: Env) {
  const b = await body<{ username?: unknown; sorterUrl?: unknown }>(req);
  const line = await findLine(env, b.username);
  const url = typeof b.sorterUrl === "string" ? b.sorterUrl.trim() : "";
  if (url) {
    try {
      assertHttpUrl(url);
    } catch {
      throw new HttpError(400, "the sorter link must start with http:// or https://");
    }
  }
  const login: LineLogin = { name: line.name, serverUrls: line.serverUrls, password: line.password, sorterUrl: url || undefined };
  await env.DB.prepare("UPDATE lines SET data_enc = ? WHERE username = ?").bind(await sealJson(env.CONFIG_KEY, login), line.username).run();
  return json({ ok: true, sorterUrl: url || null });
}

/** The profile with each Xtream source's sorter link taken from its line on the Lines page, when that has one. */
async function withSorterLinks(env: Env, profile: ProfileData): Promise<ProfileData> {
  const sources = await Promise.all(
    profile.sources.map(async (s) => {
      if (s.type !== "xtream" || !s.username) return s;
      const link = (await lineByUsername(env, s.username))?.sorterUrl;
      return link ? { ...s, sorterUrl: link } : s;
    }),
  );
  return { ...profile, sources };
}

/** Takes a line off the portal's list. The line itself stays on the panel. */
async function adminForgetLine(req: Request, env: Env) {
  const b = await body<{ username?: unknown }>(req);
  const line = await findLine(env, b.username);
  const users = (await allProfiles(env)).filter((pr) => xtreamLogins(pr.data.sources).some((s) => s.username === line.username));
  if (users.length) throw new HttpError(409, `${users.map((u) => u.name).join(", ")} still use this line; remove it from the profile first`);
  await env.DB.prepare("DELETE FROM lines WHERE username = ?").bind(line.username).run();
  return json({ ok: true });
}

/**
 * Creates a line on the panel (spends credits) and adds it to the portal's lines. With a profile,
 * it also goes into that profile as an Xtream source, so its TVs pick it up on their next check-in.
 */
async function adminNewLine(req: Request, env: Env) {
  const b = await body<{ profileId?: unknown; packageId?: unknown; months?: unknown; name?: unknown }>(req);
  const months = Number(b.months);
  if (!RENEW_MONTHS.includes(months)) throw new HttpError(400, `months must be one of ${RENEW_MONTHS.join(", ")}`);
  if (typeof b.packageId !== "string" || !b.packageId) throw new HttpError(400, "packageId is required");
  const profileId = typeof b.profileId === "string" && b.profileId ? b.profileId : null;
  const row = profileId ? await env.DB.prepare("SELECT name FROM profiles WHERE id = ?").bind(profileId).first<{ name: string }>() : null;
  const profile = profileId ? await loadProfile(env, profileId) : null;
  if (profileId && (!row || !profile)) throw new HttpError(404, "profile not found");
  const name = typeof b.name === "string" && b.name.trim() ? b.name.trim().slice(0, 60) : "8K line";
  const p = panel(env);
  const created = await viaPanel(() => p.newLine(months, b.packageId as string, `Family TV: ${row?.name ?? name}`.slice(0, 100)));
  const login = loginFromLineUrl(created.url);
  // The credits are spent either way; hand back the link so the line can be added by hand.
  if (!login) return json({ ok: false, url: created.url, error: "Line created, but its link wasn't a normal Xtream link. Add it by hand." });
  await saveLine(env, login.username, { name, serverUrls: [login.serverUrl], password: login.password });
  await checkLine(env, p, login.username, login.password);
  if (!profile) return json({ ok: true, username: login.username });
  const sourceId = crypto.randomUUID();
  const source: Source = { id: sourceId, type: "xtream", name, serverUrls: [login.serverUrl], username: login.username, password: login.password };
  profile.sources.push(source);
  await env.DB.prepare("UPDATE profiles SET data_enc = ?, updated_at = ? WHERE id = ?").bind(await sealJson(env.CONFIG_KEY, profile), Date.now(), profileId).run();
  const { results } = await env.DB.prepare("SELECT id FROM devices WHERE profile_id = ?").bind(profileId).all<{ id: string }>();
  for (const d of results) await queueCommand(env, d.id, "refresh", null);
  // The profile page adds this to its unsaved copy, so a later Save there keeps the line.
  return json({ ok: true, sourceId, username: login.username, source: { ...source, password: MASK } });
}

async function appDownload(env: Env, method: string, key = APK_KEY): Promise<Response> {
  const obj = env.APPS ? await env.APPS.get(key) : null;
  if (!obj) return json({ error: "no app uploaded yet" }, 404);
  const headers = {
    "content-type": "application/vnd.android.package-archive",
    "content-disposition": `attachment; filename="${key}"`,
    "content-length": String(obj.size),
    "cache-control": "no-store",
  };
  return new Response(method === "HEAD" ? null : obj.body, { headers });
}

/** The Rust app's update feed as its CI wrote it; 404 until the first build is published. */
async function rustLatest(env: Env, method: string): Promise<Response> {
  const obj = env.APPS ? await env.APPS.get(RUST_LATEST_KEY) : null;
  if (!obj) return json({ error: "nothing published yet" }, 404);
  return new Response(method === "HEAD" ? null : obj.body, { headers: { "content-type": "application/json", "cache-control": "no-store" } });
}

// ---------- router ----------

function clip(s: string | undefined | null, n = 80) {
  return s ? String(s).slice(0, n) : null;
}

function country(req: Request) {
  return ((req as Request & { cf?: { country?: string } }).cf?.country as string | undefined) ?? null;
}

export async function handle(req: Request, env: Env): Promise<Response> {
  const url = new URL(req.url);
  const p = url.pathname;
  const m = req.method;

  if (m === "GET" && (p === "/" || p === "/admin" || p === "/admin/")) {
    return new Response(ADMIN_PAGE, {
      headers: {
        "content-type": "text/html; charset=utf-8",
        "content-security-policy": "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'self'",
        "x-frame-options": "DENY",
      },
    });
  }

  // TV app download: short enough to type into Downloader on a remote.
  if ((m === "GET" || m === "HEAD") && (p === "/tv" || p === "/app.apk")) return appDownload(env, m);
  if ((m === "GET" || m === "HEAD") && (p === "/tvrust" || p === "/tvrust/")) return appDownload(env, m, RUST_APK_KEY);
  if ((m === "GET" || m === "HEAD") && p === "/tvrust/latest.json") return rustLatest(env, m);

  // Device endpoints
  if (m === "POST" && p === "/api/device/register") return deviceRegister(req, env);
  if (m === "GET" && p === "/api/device/config") return deviceConfig(req, env);
  if (m === "POST" && p === "/api/device/heartbeat") return deviceHeartbeat(req, env);
  if (m === "POST" && p === "/api/device/diagnostics") return deviceDiagnostics(req, env);
  if (m === "POST" && p === "/api/device/reachability") return deviceReachability(req, env);
  if (m === "POST" && p === "/api/device/categories") return deviceCategories(req, env);
  if (m === "POST" && p === "/api/device/trakt/code") return traktDeviceCode(req, env);
  if (m === "POST" && p === "/api/device/trakt/token") return traktDeviceToken(req, env);
  if (m === "POST" && p === "/api/device/trakt/refresh") return traktRefresh(req, env);
  if (m === "POST" && p === "/api/device/trakt/revoke") return traktRevoke(req, env);
  if (m === "POST" && p === "/api/device/realdebrid/link") return rdLink(req, env);
  if (["GET", "PUT", "DELETE"].includes(m) && p === "/api/device/realdebrid/api-key") return rdApiKey(req, env, url);
  if ((m === "GET" || m === "POST") && p === "/api/device/realdebrid/token") return rdToken(req, env);
  if (m === "POST" && p === "/api/device/realdebrid/unlink") return rdDisconnect(env, await requirePaired(req, env));
  if (m === "GET" && p === "/api/device/progress") return progressPull(req, env, url);
  if (m === "POST" && p === "/api/device/progress") return progressPush(req, env);
  if (m === "POST" && p === "/api/device/usage") return usagePush(req, env);
  let mm = p.match(/^\/api\/device\/commands\/([a-f0-9]+)\/ack$/);
  if (m === "POST" && mm) return deviceAck(req, env, mm[1]);

  // Admin endpoints
  if (p.startsWith("/api/admin/")) {
    requireAdmin(req, env);
    if (m === "GET" && p === "/api/admin/devices") return adminDevices(env);
    if (m === "POST" && p === "/api/admin/pair") return adminPair(req, env);
    if (m === "GET" && p === "/api/admin/profiles") return adminProfiles(env);
    if (m === "POST" && p === "/api/admin/profiles") return adminSaveProfile(req, env, null);
    if (m === "GET" && p === "/api/admin/verdicts") return json(VERDICTS);
    if (m === "GET" && p === "/api/admin/panel") return adminPanel(env);
    if (m === "GET" && p === "/api/admin/lines") return adminLines(env, url.searchParams.get("refresh") === "1");
    if (m === "POST" && p === "/api/admin/lines/renew") return adminRenewLine(req, env);
    if (m === "POST" && p === "/api/admin/lines/new") return adminNewLine(req, env);
    if (m === "POST" && p === "/api/admin/lines/add") return adminAddLines(req, env);
    if (m === "POST" && p === "/api/admin/lines/forget") return adminForgetLine(req, env);
    if (m === "POST" && p === "/api/admin/lines/sorter") return adminLineSorter(req, env);
    if (["GET", "PUT", "DELETE"].includes(m) && (mm = p.match(/^\/api\/admin\/profiles\/([a-f0-9]+)\/realdebrid\/api-key$/))) return adminRdApiKey(req, env, url, mm[1]);
    if (m === "GET" && (mm = p.match(/^\/api\/admin\/profiles\/([a-f0-9]+)\/categories$/))) return adminProfileCategories(env, mm[1]);
    if (m === "POST" && (mm = p.match(/^\/api\/admin\/profiles\/([a-f0-9]+)\/primary-server$/))) return adminPreferServer(req, env, mm[1]);
    if (m === "DELETE" && (mm = p.match(/^\/api\/admin\/profiles\/([a-f0-9]+)\/realdebrid$/))) return rdDisconnect(env, mm[1]);
    if ((mm = p.match(/^\/api\/admin\/profiles\/([a-f0-9]+)$/))) {
      if (m === "PUT") return adminSaveProfile(req, env, mm[1]);
      if (m === "DELETE") {
        await env.DB.prepare("DELETE FROM profiles WHERE id = ?").bind(mm[1]).run();
        return json({ ok: true });
      }
    }
    if ((mm = p.match(/^\/api\/admin\/devices\/([a-f0-9]+)$/))) {
      if (m === "GET") return adminDeviceDetail(env, mm[1]);
      if (m === "PATCH") return adminUpdateDevice(req, env, mm[1]);
      if (m === "DELETE") {
        await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(mm[1]).run();
        return json({ ok: true });
      }
    }
    if (m === "POST" && (mm = p.match(/^\/api\/admin\/devices\/([a-f0-9]+)\/commands$/))) return adminCommand(req, env, mm[1]);
  }

  return json({ error: "not found" }, 404);
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      return await handle(req, env);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      console.error(e);
      return json({ error: "internal error" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;
