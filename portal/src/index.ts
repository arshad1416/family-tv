import { openJson, pairCode, randomId, sealJson, sha256Hex, timingSafeEqual } from "./crypto";
import { maskSecrets, mergeSecrets, profileHosts, validateProfile, VERDICTS, type ProfileData } from "./model";
import { probe } from "./reachability";
import { ADMIN_PAGE } from "./admin-page";
import { daysLeft, loginFromLineUrl, Panel, PanelError, RENEW_MONTHS } from "./panel";

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
    profile,
    commands: await takeCommands(env, d.id, now),
    latestApp: await latestApp(env, req),
    // The client id is public (it goes in every Trakt API call); the secret never leaves the Worker.
    trakt: traktConfigured(env) ? { clientId: env.TRAKT_CLIENT_ID } : null,
    subtitlesApiKey: subtitlesApiKey(env),
    tmdbApiKey: tmdbApiKey(env),
    tvdbApiKey: tvdbApiKey(env),
  });
}

async function deviceHeartbeat(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  const b = await body<{ appVersion?: string; status?: unknown }>(req);
  const now = Date.now();
  await env.DB.prepare("UPDATE devices SET last_seen = ?, app_version = COALESCE(?, app_version), status_json = ?, country = ? WHERE id = ?")
    .bind(now, clip(b.appVersion), JSON.stringify(b.status ?? null).slice(0, 8000), country(req), d.id)
    .run();
  return json({
    paired: !!d.profile_id,
    commands: d.profile_id ? await takeCommands(env, d.id, now) : [],
    latestApp: d.profile_id ? await latestApp(env, req) : null,
  });
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
  return json({ devices: results.map((r) => ({ ...r, status: r.status_json ? JSON.parse(r.status_json as string) : null, status_json: undefined })) });
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
  });
}

async function adminProfiles(env: Env) {
  const { results } = await env.DB.prepare("SELECT id, name, data_enc, updated_at FROM profiles ORDER BY name").all<{
    id: string;
    name: string;
    data_enc: string;
    updated_at: number;
  }>();
  const out = await Promise.all(
    results.map(async (r) => ({ id: r.id, name: r.name, updated_at: r.updated_at, data: maskSecrets(await openJson<ProfileData>(env.CONFIG_KEY, r.data_enc)) })),
  );
  return json({ profiles: out });
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
  if (id) {
    const existing = await loadProfile(env, id);
    if (!existing) throw new HttpError(404, "profile not found");
    data = mergeSecrets(data, existing);
    await env.DB.prepare("UPDATE profiles SET name = ?, data_enc = ?, updated_at = ? WHERE id = ?")
      .bind(b.name.trim(), await sealJson(env.CONFIG_KEY, data), now, id)
      .run();
    // Tell every TV on this profile to reload.
    const { results } = await env.DB.prepare("SELECT id FROM devices WHERE profile_id = ?").bind(id).all<{ id: string }>();
    for (const d of results) await queueCommand(env, d.id, "refresh", null);
  } else {
    id = randomId(8);
    data = mergeSecrets(data, null);
    await env.DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES (?,?,?,?,?)")
      .bind(id, b.name.trim(), await sealJson(env.CONFIG_KEY, data), now, now)
      .run();
  }
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

interface LineRow {
  profile_id: string;
  source_id: string;
  username: string;
  expire: string | null;
  enabled: number | null;
  max_connections: number | null;
  note: string | null;
  error: string | null;
  checked_at: number;
}

async function allProfiles(env: Env) {
  const { results } = await env.DB.prepare("SELECT id, name, data_enc FROM profiles ORDER BY name").all<{ id: string; name: string; data_enc: string }>();
  return Promise.all(results.map(async (r) => ({ id: r.id, name: r.name, data: await openJson<ProfileData>(env.CONFIG_KEY, r.data_enc) })));
}

async function checkLine(env: Env, p: Panel, profileId: string, sourceId: string, username: string, password: string) {
  const now = Date.now();
  try {
    const l = await p.line(username, password);
    await env.DB.prepare(
      `INSERT INTO line_status (profile_id, source_id, username, expire, enabled, max_connections, note, error, checked_at) VALUES (?,?,?,?,?,?,?,NULL,?)
       ON CONFLICT (profile_id, source_id) DO UPDATE SET username = excluded.username, expire = excluded.expire, enabled = excluded.enabled,
         max_connections = excluded.max_connections, note = excluded.note, error = NULL, checked_at = excluded.checked_at`,
    )
      .bind(profileId, sourceId, username, l.expire, l.enabled ? 1 : 0, l.maxConnections, l.note, now)
      .run();
  } catch (e) {
    if (!(e instanceof PanelError)) throw e;
    // Keep the last good expiry on screen; just note why this check failed.
    await env.DB.prepare(
      `INSERT INTO line_status (profile_id, source_id, username, error, checked_at) VALUES (?,?,?,?,?)
       ON CONFLICT (profile_id, source_id) DO UPDATE SET username = excluded.username, error = excluded.error, checked_at = excluded.checked_at`,
    )
      .bind(profileId, sourceId, username, e.message, now)
      .run();
  }
}

/**
 * Every Xtream login across the profiles, with what the panel last said about it. Lines not
 * checked for [LINE_STALE_MS] (or all, with [force]) are re-checked one at a time, gently.
 */
async function adminLines(env: Env, force: boolean) {
  const profiles = await allProfiles(env);
  const { results } = await env.DB.prepare("SELECT * FROM line_status").all<LineRow>();
  const known = new Map(results.map((r) => [r.profile_id + "/" + r.source_id, r]));
  const logins = profiles.flatMap((pr) =>
    pr.data.sources.filter((s) => s.type === "xtream" && s.username && s.password).map((s) => ({ profile: pr, source: s })),
  );
  if (panelConfigured(env)) {
    const p = panel(env);
    const now = Date.now();
    for (const { profile, source } of logins) {
      const row = known.get(profile.id + "/" + source.id);
      if (force || !row || row.username !== source.username || now - row.checked_at > LINE_STALE_MS) {
        await checkLine(env, p, profile.id, source.id, source.username!, source.password!);
      }
    }
  }
  // Forget lines whose source was removed from its profile.
  const live = new Set(logins.map((l) => l.profile.id + "/" + l.source.id));
  const gone = results.filter((r) => !live.has(r.profile_id + "/" + r.source_id));
  if (gone.length) await env.DB.batch(gone.map((r) => env.DB.prepare("DELETE FROM line_status WHERE profile_id = ? AND source_id = ?").bind(r.profile_id, r.source_id)));
  const fresh = new Map((await env.DB.prepare("SELECT * FROM line_status").all<LineRow>()).results.map((r) => [r.profile_id + "/" + r.source_id, r]));
  const lines = logins.map(({ profile, source }) => {
    const r = fresh.get(profile.id + "/" + source.id);
    return {
      profileId: profile.id,
      profileName: profile.name,
      sourceId: source.id,
      sourceName: source.name,
      username: source.username,
      expire: r?.expire ?? null,
      daysLeft: r?.expire ? daysLeft(r.expire) : null,
      enabled: r?.enabled == null ? null : r.enabled === 1,
      maxConnections: r?.max_connections ?? null,
      note: r?.note ?? null,
      error: r?.error ?? null,
      checkedAt: r?.checked_at ?? null,
    };
  });
  lines.sort((a, b) => (a.daysLeft ?? 1e9) - (b.daysLeft ?? 1e9));
  return json({ configured: panelConfigured(env), lines });
}

async function findLogin(env: Env, profileId: unknown, sourceId: unknown) {
  if (typeof profileId !== "string" || typeof sourceId !== "string") throw new HttpError(400, "profileId and sourceId are required");
  const profile = await loadProfile(env, profileId);
  const source = profile?.sources.find((s) => s.id === sourceId);
  if (!source) throw new HttpError(404, "line not found");
  if (source.type !== "xtream" || !source.username || !source.password) throw new HttpError(400, "only Xtream logins can be renewed");
  return { username: source.username, password: source.password };
}

/** Spends credits: the admin page confirms first. */
async function adminRenewLine(req: Request, env: Env) {
  const b = await body<{ profileId?: unknown; sourceId?: unknown; months?: unknown }>(req);
  const months = Number(b.months);
  if (!RENEW_MONTHS.includes(months)) throw new HttpError(400, `months must be one of ${RENEW_MONTHS.join(", ")}`);
  const login = await findLogin(env, b.profileId, b.sourceId);
  const p = panel(env);
  await viaPanel(() => p.renew(login.username, login.password, months));
  await checkLine(env, p, b.profileId as string, b.sourceId as string, login.username, login.password);
  return json({ ok: true });
}

/**
 * Creates a line on the panel (spends credits) and adds it to the profile as an Xtream source,
 * so the profile's TVs pick it up on their next check-in.
 */
async function adminNewLine(req: Request, env: Env) {
  const b = await body<{ profileId?: unknown; packageId?: unknown; months?: unknown; name?: unknown }>(req);
  const months = Number(b.months);
  if (!RENEW_MONTHS.includes(months)) throw new HttpError(400, `months must be one of ${RENEW_MONTHS.join(", ")}`);
  if (typeof b.profileId !== "string" || typeof b.packageId !== "string" || !b.packageId) throw new HttpError(400, "profileId and packageId are required");
  const profileId = b.profileId;
  const row = await env.DB.prepare("SELECT name FROM profiles WHERE id = ?").bind(profileId).first<{ name: string }>();
  const profile = await loadProfile(env, profileId);
  if (!row || !profile) throw new HttpError(404, "profile not found");
  const p = panel(env);
  const created = await viaPanel(() => p.newLine(months, b.packageId as string, `Family TV: ${row.name}`.slice(0, 100)));
  const login = loginFromLineUrl(created.url);
  // The credits are spent either way; hand back the link so the line can be added by hand.
  if (!login) return json({ ok: false, url: created.url, error: "Line created, but its link wasn't a normal Xtream link. Add it to the profile by hand." });
  const sourceId = crypto.randomUUID();
  const name = typeof b.name === "string" && b.name.trim() ? b.name.trim().slice(0, 60) : "8K line";
  profile.sources.push({ id: sourceId, type: "xtream", name, serverUrls: [login.serverUrl], username: login.username, password: login.password });
  await env.DB.prepare("UPDATE profiles SET data_enc = ?, updated_at = ? WHERE id = ?").bind(await sealJson(env.CONFIG_KEY, profile), Date.now(), profileId).run();
  const { results } = await env.DB.prepare("SELECT id FROM devices WHERE profile_id = ?").bind(profileId).all<{ id: string }>();
  for (const d of results) await queueCommand(env, d.id, "refresh", null);
  await checkLine(env, p, profileId, sourceId, login.username, login.password);
  return json({ ok: true, sourceId, username: login.username });
}

async function appDownload(env: Env, method: string): Promise<Response> {
  const obj = env.APPS ? await env.APPS.get(APK_KEY) : null;
  if (!obj) return json({ error: "no app uploaded yet" }, 404);
  const headers = {
    "content-type": "application/vnd.android.package-archive",
    "content-disposition": 'attachment; filename="family-tv.apk"',
    "content-length": String(obj.size),
    "cache-control": "no-store",
  };
  return new Response(method === "HEAD" ? null : obj.body, { headers });
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

  // Device endpoints
  if (m === "POST" && p === "/api/device/register") return deviceRegister(req, env);
  if (m === "GET" && p === "/api/device/config") return deviceConfig(req, env);
  if (m === "POST" && p === "/api/device/heartbeat") return deviceHeartbeat(req, env);
  if (m === "POST" && p === "/api/device/diagnostics") return deviceDiagnostics(req, env);
  if (m === "POST" && p === "/api/device/reachability") return deviceReachability(req, env);
  if (m === "POST" && p === "/api/device/trakt/code") return traktDeviceCode(req, env);
  if (m === "POST" && p === "/api/device/trakt/token") return traktDeviceToken(req, env);
  if (m === "POST" && p === "/api/device/trakt/refresh") return traktRefresh(req, env);
  if (m === "POST" && p === "/api/device/trakt/revoke") return traktRevoke(req, env);
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
