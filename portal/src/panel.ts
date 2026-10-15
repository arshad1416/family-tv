// The 8K Gold reseller panel (cms-8k.com, API v1.81): credits, packages, and the lines behind
// each profile's Xtream logins. Every call sends the reseller key as a query parameter, which is
// the only auth the panel supports, so the key stays inside this Worker.

export const PANEL_API = "https://cms-8k.com/api/api.php";
export const RENEW_MONTHS = [1, 3, 6, 12];

export interface Reseller {
  username: string;
  credits: number;
  enabled: boolean;
}

export interface PanelPackage {
  id: string;
  name: string;
}

export interface LineInfo {
  /** YYYY-MM-DD, as the panel reports it. */
  expire: string;
  enabled: boolean;
  userId: string | null;
  note: string | null;
  maxConnections: number | null;
}

export interface NewLine {
  userId: string | null;
  /** The playlist URL the panel returns; carries the new line's server, username and password. */
  url: string;
}

export class PanelError extends Error {}

type Fetcher = typeof fetch;

/** The panel answers {"status":"true", ...} on success and {"status":"false"|"error","result":"..."} otherwise. */
function ok(r: unknown): Record<string, unknown> {
  const o = (Array.isArray(r) ? r[0] : r) as Record<string, unknown> | undefined;
  if (!o || typeof o !== "object") throw new PanelError("empty answer from the panel");
  if (String(o.status) !== "true") throw new PanelError(String(o.result ?? o.message ?? "the panel refused the request"));
  return o;
}

const str = (v: unknown): string | null => (v == null || v === "" ? null : String(v));

export class Panel {
  constructor(
    private apiKey: string,
    private fetcher: Fetcher = fetch,
    private base = PANEL_API,
  ) {}

  private async call(params: Record<string, string>): Promise<unknown> {
    const url = this.base + "?" + new URLSearchParams({ ...params, api_key: this.apiKey });
    let res: Response;
    try {
      // The first call after the panel has been idle can take ~30 s.
      res = await this.fetcher(url, { headers: { "User-Agent": "FamilyTV-Portal/1" }, signal: AbortSignal.timeout(45_000) });
    } catch (e) {
      throw new PanelError(`panel unreachable (${(e as Error).name || "error"})`);
    }
    const text = await res.text();
    if (!res.ok) throw new PanelError(`panel answered HTTP ${res.status}`);
    try {
      return JSON.parse(text);
    } catch {
      throw new PanelError("panel answered something other than JSON");
    }
  }

  async reseller(): Promise<Reseller> {
    const o = ok(await this.call({ action: "reseller" }));
    return { username: String(o.username ?? ""), credits: Number(o.credits ?? 0), enabled: String(o.enabled ?? "1") === "1" };
  }

  async packages(): Promise<PanelPackage[]> {
    const r = await this.call({ action: "bouquet" });
    if (!Array.isArray(r)) ok(r); // throws with the panel's message
    return (r as Record<string, unknown>[]).filter((p) => p && p.id != null).map((p) => ({ id: String(p.id), name: String(p.name ?? p.id) }));
  }

  async line(username: string, password: string): Promise<LineInfo> {
    const o = ok(await this.call({ action: "device_info", username, password }));
    const expire = str(o.expire);
    if (!expire) throw new PanelError("the panel didn't say when this line expires");
    const max = Number(o.max_connections ?? o.connections ?? NaN);
    return {
      expire,
      enabled: String(o.enabled ?? "1") === "1",
      userId: str(o.user_id),
      note: str(o.note ?? o.notes)?.trim() || null,
      maxConnections: Number.isFinite(max) ? max : null,
    };
  }

  async renew(username: string, password: string, months: number): Promise<void> {
    if (!RENEW_MONTHS.includes(months)) throw new PanelError(`months must be one of ${RENEW_MONTHS.join(", ")}`);
    ok(await this.call({ action: "renew", type: "m3u", username, password, sub: String(months) }));
  }

  async newLine(months: number, packageId: string, notes: string): Promise<NewLine> {
    if (!RENEW_MONTHS.includes(months)) throw new PanelError(`months must be one of ${RENEW_MONTHS.join(", ")}`);
    const params: Record<string, string> = { action: "new", type: "m3u", sub: String(months), pack: packageId };
    if (notes) params.notes = notes;
    const o = ok(await this.call(params));
    const url = str(o.url);
    if (!url) throw new PanelError("the panel created the line but didn't return its link; find it in the panel");
    return { userId: str(o.user_id), url };
  }
}

/** Server, username and password from a line's playlist link (http://host:port/get.php?username=…&password=…). */
export function loginFromLineUrl(link: string): { serverUrl: string; username: string; password: string } | null {
  try {
    const u = new URL(link);
    const username = u.searchParams.get("username");
    const password = u.searchParams.get("password");
    if (!username || !password || !/^https?:$/.test(u.protocol)) return null;
    return { serverUrl: `${u.protocol}//${u.host}`, username, password };
  } catch {
    return null;
  }
}

/** Whole days from [today] (YYYY-MM-DD) to [expire]; negative once it has passed. */
export function daysLeft(expire: string, today = new Date().toISOString().slice(0, 10)): number {
  return Math.round((Date.parse(expire.slice(0, 10)) - Date.parse(today)) / 86_400_000);
}
