export interface Source {
  id: string;
  type: "xtream" | "m3u";
  name: string;
  /** Xtream: one or more server base URLs, tried in order (providers often give backups). */
  serverUrls?: string[];
  username?: string;
  password?: string;
  /** M3U: playlist URL; epgUrl optional for both types. */
  m3uUrl?: string;
  epgUrl?: string;
}

export const CATEGORY_KINDS = ["live", "movie", "series"] as const;
export type CategoryKind = (typeof CATEGORY_KINDS)[number];

export interface ProfileData {
  sources: Source[];
  parentalPin?: string;
  hiddenCategories?: string[];
  /** Category names per kind in the order the TVs list them; ones not named keep the provider's order after these. */
  categoryOrder?: Partial<Record<CategoryKind, string[]>>;
}

const MAX_CATEGORIES = 1000;

/** Category names as a TV reports or the admin page saves them: strings, trimmed, de-duplicated, capped. */
export function cleanNames(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  const out: string[] = [];
  const seen = new Set<string>();
  for (const x of v) {
    if (typeof x !== "string") continue;
    const n = x.trim().slice(0, 120);
    if (!n || seen.has(n.toLowerCase())) continue;
    seen.add(n.toLowerCase());
    out.push(n);
    if (out.length >= MAX_CATEGORIES) break;
  }
  return out;
}

export const MASK = "••••••";

/** Hosts a device on this profile legitimately talks to (used to scope the reachability check). */
export function profileHosts(p: ProfileData): Set<string> {
  const hosts = new Set<string>();
  for (const s of p.sources) {
    for (const u of [...(s.serverUrls ?? []), s.m3uUrl, s.epgUrl]) {
      if (!u) continue;
      try {
        hosts.add(new URL(u).host.toLowerCase());
      } catch {
        /* ignore malformed */
      }
    }
  }
  return hosts;
}

/** Passwords never leave the portal towards the admin page; the page sends MASK back to mean "unchanged". */
export function maskSecrets(p: ProfileData): ProfileData {
  return {
    ...p,
    parentalPin: p.parentalPin ? MASK : undefined,
    sources: p.sources.map((s) => ({ ...s, password: s.password ? MASK : undefined })),
  };
}

export function mergeSecrets(incoming: ProfileData, existing: ProfileData | null): ProfileData {
  const old = new Map((existing?.sources ?? []).map((s) => [s.id, s]));
  return {
    ...incoming,
    parentalPin: incoming.parentalPin === MASK ? existing?.parentalPin : incoming.parentalPin,
    sources: incoming.sources.map((s) => ({
      ...s,
      password: s.password === MASK ? old.get(s.id)?.password : s.password,
    })),
  };
}

export function validateProfile(p: unknown): ProfileData {
  if (!p || typeof p !== "object") throw new Error("profile must be an object");
  const d = p as ProfileData;
  if (!Array.isArray(d.sources)) throw new Error("sources must be a list");
  for (const s of d.sources) {
    if (!s.id || !s.name) throw new Error("each source needs an id and a name");
    if (s.type === "xtream") {
      if (!s.serverUrls?.length || !s.username) throw new Error(`${s.name}: Xtream needs a server URL and username`);
      s.serverUrls.forEach(assertHttpUrl);
    } else if (s.type === "m3u") {
      if (!s.m3uUrl) throw new Error(`${s.name}: M3U needs a playlist URL`);
      assertHttpUrl(s.m3uUrl);
    } else {
      throw new Error(`${s.name}: type must be xtream or m3u`);
    }
    if (s.epgUrl) assertHttpUrl(s.epgUrl);
  }
  if (d.hiddenCategories !== undefined) d.hiddenCategories = cleanNames(d.hiddenCategories);
  if (d.categoryOrder !== undefined) {
    if (!d.categoryOrder || typeof d.categoryOrder !== "object") throw new Error("categoryOrder must be an object");
    const order: Partial<Record<CategoryKind, string[]>> = {};
    for (const k of CATEGORY_KINDS) {
      const names = cleanNames(d.categoryOrder[k]);
      if (names.length) order[k] = names;
    }
    d.categoryOrder = order;
  }
  return d;
}

function assertHttpUrl(u: string) {
  const url = new URL(u);
  if (url.protocol !== "http:" && url.protocol !== "https:") throw new Error(`not an http(s) URL: ${u}`);
}

/** What the TV computed, mapped to plain words and a next step for the admin page. */
export const VERDICTS: Record<string, { label: string; advice: string; severity: number }> = {
  OK: { label: "Working", advice: "Nothing to fix.", severity: 0 },
  SLOW_CONNECTION: {
    label: "Slow connection",
    advice: "The stream works but the TV's internet is too slow for smooth playback. Move the TV closer to Wi-Fi, use Ethernet, or pick a lower-quality channel.",
    severity: 1,
  },
  DNS_FILTERED: {
    label: "Blocked by a DNS filter on their network",
    advice: "The home network's DNS (AdGuard, Pi-hole, NextDNS, router parental controls) refuses this host while public DNS resolves it. Allowlist the host shown in the report on that filter.",
    severity: 3,
  },
  NETWORK_BLOCKED: {
    label: "Blocked on their network or by their ISP",
    advice: "The server answers from the internet but not from this TV's network. If no filter on the home network explains it, the ISP is blocking it; contact the provider.",
    severity: 3,
  },
  DNS_NOT_FOUND: {
    label: "Server address no longer exists",
    advice: "Public DNS can't find this host either. The provider has probably changed its server URL; get the new one and update the profile.",
    severity: 3,
  },
  SERVER_DOWN: {
    label: "Provider server down",
    advice: "Neither the TV nor the portal can reach the server. Wait, or switch the profile to a backup server URL.",
    severity: 3,
  },
  SERVER_ERROR: { label: "Provider server error", advice: "The server is reachable but returning errors. Usually temporary; contact the provider if it persists.", severity: 2 },
  BAD_LOGIN: { label: "Wrong username or password", advice: "The provider rejected the login. Check the line in your reseller panel and update the profile.", severity: 3 },
  EXPIRED: { label: "Line expired or disabled", advice: "Renew or re-enable this line in your reseller panel.", severity: 3 },
  MAX_CONNECTIONS: {
    label: "Too many devices on this line",
    advice: "The line is already at its connection limit. Stop playback elsewhere or raise the limit in your reseller panel.",
    severity: 2,
  },
  NO_INTERNET: { label: "TV has no internet", advice: "The TV can't reach anything. Check its Wi-Fi/Ethernet and the router.", severity: 3 },
};
