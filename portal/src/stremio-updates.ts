import { timingSafeEqual } from "./crypto";

// Additive namespace in the existing Worker and APPS binding. No caller-selected keys.
const ORIGIN = "https://familytv.arshadkazi.ca";
const LATEST = "stremio-lab/latest.json";
const MAX_APK = 64 * 1024 * 1024;
const MAX_FEED = 64 * 1024;
const MAX_CODE = 2_100_000_000;
const APK_TYPE = "application/vnd.android.package-archive";
interface UpdateEnv { APPS?: R2Bucket; STREMIO_LAB_PUBLISH_TOKEN?: string }
interface Feed {
  applicationId: "app.familytv.stremio"; channel: "stremio-lab"; versionCode: number;
  versionName: string; url: string; sha256: string; size: number;
}
const reply = (status: number, error: string) => new Response(JSON.stringify({ error }), {
  status, headers: { "content-type": "application/json", "cache-control": "no-store" },
});
const hex = (value: ArrayBuffer) => [...new Uint8Array(value)].map(x => x.toString(16).padStart(2, "0")).join("");
const apkKey = (code: number) => `stremio-lab/builds/${code}/app.apk`;

function validFeed(value: unknown): value is Feed {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const f = value as Record<string, unknown>;
  return Object.keys(f).sort().join(",") === "applicationId,channel,sha256,size,url,versionCode,versionName"
    && f.applicationId === "app.familytv.stremio" && f.channel === "stremio-lab"
    && typeof f.versionCode === "number" && Number.isInteger(f.versionCode) && f.versionCode > 0 && f.versionCode <= MAX_CODE
    && f.versionName === `0.1.${f.versionCode}-stremio-lab`
    && f.url === ORIGIN + "/" + apkKey(f.versionCode)
    && typeof f.sha256 === "string" && /^[a-f0-9]{64}$/.test(f.sha256)
    && typeof f.size === "number" && Number.isInteger(f.size) && f.size > 0 && f.size <= MAX_APK;
}

class FeedTooLarge extends Error {}
async function readFeed(stream: ReadableStream | null): Promise<unknown> {
  if (!stream) return null;
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_FEED) throw new FeedTooLarge();
      chunks.push(value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try { return JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes)); }
  catch { return null; }
}

async function download(req: Request, bucket: R2Bucket, key: string): Promise<Response> {
  const object = req.method === "HEAD" ? await bucket.head(key) : await bucket.get(key);
  if (!object) return reply(404, "Lab artifact not published");
  return new Response(req.method === "HEAD" ? null : (object as R2ObjectBody).body, { headers: {
    "content-type": key === LATEST ? "application/json" : APK_TYPE,
    "content-length": String(object.size), "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  } });
}

async function uploadApk(req: Request, bucket: R2Bucket, key: string): Promise<Response> {
  const length = req.headers.get("content-length");
  if (length === null) return reply(411, "content-length required");
  if (!/^[1-9][0-9]*$/.test(length) || Number(length) > MAX_APK) return reply(413, "invalid APK length");
  const checksum = req.headers.get("x-content-sha256") ?? "";
  if (!/^[a-f0-9]{64}$/.test(checksum) || !req.body) return reply(400, "APK SHA-256 and body required");
  // Native Workers FixedLengthStream checks short/long streams and preserves a known
  // size for R2. R2 verifies sha256 before committing; no full APK buffer is created.
  const fixed = new FixedLengthStream(Number(length));
  const controller = new AbortController();
  const piping = req.body.pipeTo(fixed.writable, { signal: controller.signal });
  // Observe rejection immediately, including when R2 rejects without consuming.
  const piped = piping.then(() => true, () => false);
  try {
    const object = await bucket.put(key, fixed.readable, {
      onlyIf: { etagDoesNotMatch: "*" }, sha256: checksum,
      httpMetadata: { contentType: APK_TYPE },
    });
    if (!object) {
      controller.abort();
      await fixed.readable.cancel().catch(() => {});
      await piped;
      return reply(409, "immutable versionCode already exists");
    }
    if (!await piped) return reply(400, "APK stream rejected");
    return new Response(null, { status: 201 });
  } catch {
    controller.abort();
    await fixed.readable.cancel().catch(() => {});
    await piped;
    return reply(400, "APK upload rejected");
  }
}

async function uploadFeed(req: Request, bucket: R2Bucket): Promise<Response> {
  let feed: unknown;
  try { feed = await readFeed(req.body); }
  catch (error) {
    if (error instanceof FeedTooLarge) return reply(413, "feed too large");
    return reply(400, "feed stream rejected");
  }
  if (!validFeed(feed)) return reply(400, "invalid Lab feed");
  const apk = await bucket.head(apkKey(feed.versionCode));
  if (!apk || apk.size !== feed.size || !apk.checksums.sha256 || hex(apk.checksums.sha256) !== feed.sha256)
    return reply(409, "matching checksummed APK required");
  const current = await bucket.get(LATEST);
  if (current) {
    let old: unknown;
    try { old = await readFeed(current.body); } catch { return reply(409, "current feed invalid"); }
    if (!validFeed(old) || old.versionCode >= feed.versionCode) return reply(409, "current feed invalid or version not increasing");
  }
  // Compare-and-swap closes the read/write race; an absent feed is create-only too.
  const object = await bucket.put(LATEST, JSON.stringify(feed) + "\n", {
    onlyIf: current ? { etagMatches: current.etag } : { etagDoesNotMatch: "*" },
    httpMetadata: { contentType: "application/json" },
  });
  return object ? new Response(null, { status: 200 }) : reply(409, "feed changed; use a later versionCode");
}

/** null means this is an unrelated existing portal route. Paired auth is supplied
 * by index.ts so these downloads use precisely the existing D1 device contract. */
export async function stremioUpdates(
  req: Request, env: UpdateEnv, requirePaired: () => Promise<void>,
): Promise<Response | null> {
  const url = new URL(req.url);
  const publishing = url.pathname.startsWith("/api/stremio-lab/");
  if (!publishing && !url.pathname.startsWith("/stremio-lab/")) return null;
  if (req.url !== ORIGIN + url.pathname) return reply(404, "noncanonical Lab URL");
  const prefix = publishing ? "/api/stremio-lab/publish/" : "/stremio-lab/";
  const path = url.pathname.slice(prefix.length);
  const match = /^builds\/([1-9][0-9]*)\/app\.apk$/.exec(path);
  if (!url.pathname.startsWith(prefix) || (path !== "latest.json" && (!match || Number(match[1]) > MAX_CODE)))
    return reply(404, "unknown Lab path");
  if (publishing) {
    const auth = req.headers.get("authorization") ?? "";
    const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!env.STREMIO_LAB_PUBLISH_TOKEN || !token || !timingSafeEqual(token, env.STREMIO_LAB_PUBLISH_TOKEN))
      return reply(401, "Lab publisher token required");
  } else { await requirePaired(); }
  if (!["GET", "HEAD", ...(publishing ? ["PUT"] : [])].includes(req.method)) return reply(405, "method not allowed");
  if (!env.APPS) return reply(503, "artifact storage unavailable");
  const key = path === "latest.json" ? LATEST : apkKey(Number(match![1]));
  if (req.method !== "PUT") return download(req, env.APPS, key);
  return key === LATEST ? uploadFeed(req, env.APPS) : uploadApk(req, env.APPS, key);
}
