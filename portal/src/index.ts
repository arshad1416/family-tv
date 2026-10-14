import { openJson, pairCode, randomId, sealJson, sha256Hex, timingSafeEqual } from "./crypto";
import { maskSecrets, mergeSecrets, profileHosts, validateProfile, VERDICTS, type ProfileData } from "./model";
import { probe } from "./reachability";
import { ADMIN_PAGE } from "./admin-page";

export interface Env {
  DB: D1Database;
  ADMIN_TOKEN: string;
  CONFIG_KEY: string;
  LATEST_APP_VERSION_CODE?: string;
  LATEST_APP_URL?: string;
  APPS?: R2Bucket;
}

const APK_KEY = "family-tv.apk";

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

function latestApp(env: Env) {
  const code = Number(env.LATEST_APP_VERSION_CODE ?? "0");
  return code > 0 && env.LATEST_APP_URL ? { versionCode: code, url: env.LATEST_APP_URL } : null;
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
    latestApp: latestApp(env),
  });
}

async function deviceHeartbeat(req: Request, env: Env) {
  const d = await requireDevice(req, env);
  const b = await body<{ appVersion?: string; status?: unknown }>(req);
  const now = Date.now();
  await env.DB.prepare("UPDATE devices SET last_seen = ?, app_version = COALESCE(?, app_version), status_json = ?, country = ? WHERE id = ?")
    .bind(now, clip(b.appVersion), JSON.stringify(b.status ?? null).slice(0, 8000), country(req), d.id)
    .run();
  return json({ paired: !!d.profile_id, commands: d.profile_id ? await takeCommands(env, d.id, now) : [] });
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
